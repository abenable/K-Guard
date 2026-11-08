package com.kguard.app.data.repository

import android.util.Log
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.model.FamilyPrivacy
import com.kguard.app.data.model.ParentAction
import com.kguard.app.data.model.QuietHours
import com.kguard.app.data.model.SafetyEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Auth + family data. All writes go through Firestore rules; privileged ones go through Cloud Functions. */
object FamilyRepository {

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val functions get() = FirebaseFunctions.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser

    fun authState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener {
            Log.d("KGuard", "FamilyRepository: authState changed, signedIn=${it.currentUser != null}")
            trySend(it.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signInWithCredential(credential: AuthCredential) {
        Log.d("KGuard", "FamilyRepository: signInWithCredential")
        auth.signInWithCredential(credential).await()
        Log.d("KGuard", "FamilyRepository: signInWithCredential success")
    }

    /**
     * Account-less sign-in for pairing a child device: no Google account is needed on the
     * child's phone. The anonymous identity is just a placeholder UID; the 6-digit pairing
     * code is the real credential that binds it to the child profile. Requires the Anonymous
     * sign-in provider to be enabled in the Firebase console.
     */
    suspend fun signInAnonymously() {
        Log.d("KGuard", "FamilyRepository: signInAnonymously")
        auth.signInAnonymously().await()
        Log.d("KGuard", "FamilyRepository: signInAnonymously success")
    }

    fun signOut() {
        Log.d("KGuard", "FamilyRepository: signOut")
        auth.signOut()
    }

    /** Creates the family on first sign-in (server side) and returns its id. */
    suspend fun ensureFamily(): String {
        Log.d("KGuard", "FamilyRepository: ensureFamily calling createFamily Cloud Function")
        val res = functions.getHttpsCallable("createFamily").call().await()
        @Suppress("UNCHECKED_CAST")
        val data = res.data as Map<String, Any?>
        val familyId = data["familyId"] as? String
            ?: throw IllegalStateException("createFamily response missing familyId.")
        Log.d("KGuard", "FamilyRepository: ensureFamily success, familyId=$familyId")
        return familyId
    }

    // ---------- children ----------
    fun children(familyId: String): Flow<List<ChildProfile>> = callbackFlow {
        Log.d("KGuard", "FamilyRepository: children flow started for familyId=$familyId")
        val reg = db.collection("families/$familyId/children")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("KGuard", "FamilyRepository: children listener error", err)
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { d ->
                    d.toObject(ChildProfile::class.java)?.copy(id = d.id)
                } ?: emptyList()
                Log.d("KGuard", "FamilyRepository: children loaded count=${list.size}")
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun saveChild(familyId: String, child: ChildProfile): String {
        Log.d("KGuard", "FamilyRepository: saveChild familyId=$familyId, childId=${child.id}")
        val col = db.collection("families/$familyId/children")
        val data = hashMapOf<String, Any?>(
            "name" to child.name.trim(),
            "email" to child.email.trim(),
            "photoUrl" to child.photoUrl.trim(),
            "ageProfile" to child.ageProfile,
            "categoryRules" to child.categoryRules,
            "allowReveal" to child.allowReveal
        )
        val id = if (child.id.isEmpty()) {
            data["createdAt"] = FieldValue.serverTimestamp()
            val ref = col.add(data).await()
            Log.d("KGuard", "FamilyRepository: saveChild created new child with id=${ref.id}")
            ref.id
        } else {
            val docRef = col.document(child.id)
            // createdAt must always exist: children() orders by it, so backfill it if the doc lacks one.
            if (docRef.get().await().getTimestamp("createdAt") == null) {
                data["createdAt"] = FieldValue.serverTimestamp()
            }
            docRef.update(data).await()
            Log.d("KGuard", "FamilyRepository: saveChild updated child with id=${child.id}")
            child.id
        }
        return id
    }

    /** A paired child's phone may read only its own profile (see firestore.rules), never the whole list. */
    fun linkedChild(familyId: String, uid: String): Flow<List<ChildProfile>> = callbackFlow {
        val reg = db.collection("families/$familyId/children")
            .whereEqualTo("linkedUid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("KGuard", "FamilyRepository: linked child listener error", err)
                    close(err)
                    return@addSnapshotListener
                }
                trySend(snap?.documents.orEmpty().mapNotNull { d -> d.toObject(ChildProfile::class.java)?.copy(id = d.id) })
            }
        awaitClose { reg.remove() }
    }

    suspend fun updateChildPhoto(familyId: String, childId: String, photoUrl: String) {
        db.document("families/$familyId/children/$childId").update("photoUrl", photoUrl).await()
    }

    suspend fun deleteChild(familyId: String, childId: String) {
        Log.d("KGuard", "FamilyRepository: deleteChild familyId=$familyId, childId=$childId")
        db.document("families/$familyId/children/$childId").delete().await()
        Log.d("KGuard", "FamilyRepository: deleteChild success for childId=$childId")
    }

    suspend fun createPairingCode(childId: String): String {
        Log.d("KGuard", "FamilyRepository: createPairingCode for childId=$childId")
        val res = functions.getHttpsCallable("createPairingCode").call(mapOf("childId" to childId)).await()
        @Suppress("UNCHECKED_CAST")
        val code = (res.data as Map<String, Any?>)["code"] as? String
            ?: throw IllegalStateException("createPairingCode response missing code.")
        Log.d("KGuard", "FamilyRepository: createPairingCode success")
        return code
    }

    suspend fun redeemPairingCode(code: String): Map<String, Any?> {
        Log.d("KGuard", "FamilyRepository: redeemPairingCode")
        val res = functions.getHttpsCallable("redeemPairingCode").call(mapOf("code" to code)).await()
        @Suppress("UNCHECKED_CAST")
        val data = res.data as Map<String, Any?>
        Log.d("KGuard", "FamilyRepository: redeemPairingCode success")
        return data
    }

    // ---------- events ----------
    fun events(familyId: String, limit: Long = 100): Flow<List<SafetyEvent>> = callbackFlow {
        Log.d("KGuard", "FamilyRepository: events flow started for familyId=$familyId")
        val reg = db.collection("families/$familyId/events")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("KGuard", "FamilyRepository: events listener error", err)
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { d ->
                    d.toObject(SafetyEvent::class.java)?.copy(id = d.id)
                } ?: emptyList()
                Log.d("KGuard", "FamilyRepository: events loaded count=${list.size}")
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    /** Lightweight listener for open alerts only; drives the dashboard badge without the 100-event cap. */
    fun openEvents(familyId: String): Flow<List<SafetyEvent>> = callbackFlow {
        Log.d("KGuard", "FamilyRepository: openEvents flow started for familyId=$familyId")
        val reg = db.collection("families/$familyId/events")
            .whereEqualTo("status", "open")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("KGuard", "FamilyRepository: openEvents listener error", err)
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { d ->
                    d.toObject(SafetyEvent::class.java)?.copy(id = d.id)
                } ?: emptyList()
                Log.d("KGuard", "FamilyRepository: openEvents loaded count=${list.size}")
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun reviewEvent(familyId: String, eventId: String, action: ParentAction, note: String) {
        Log.d("KGuard", "FamilyRepository: reviewEvent eventId=$eventId, action=${action.id}")
        db.document("families/$familyId/events/$eventId").update(
            mapOf(
                "status" to "reviewed",
                "parentAction" to action.id,
                "parentNote" to note.trim().take(300),
                "reviewedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        Log.d("KGuard", "FamilyRepository: reviewEvent success for eventId=$eventId")
    }

    // ---------- privacy ----------
    fun privacy(familyId: String): Flow<FamilyPrivacy> = callbackFlow {
        val reg = db.document("families/$familyId").addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e("KGuard", "FamilyRepository: privacy listener error", err)
                close(err)
                return@addSnapshotListener
            }
            @Suppress("UNCHECKED_CAST")
            val p = snap?.get("privacy") as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val q = p?.get("quietHours") as? Map<String, Any?>
            trySend(
                FamilyPrivacy(
                    notifyOn = p?.get("notifyOn") as? String ?: FamilyPrivacy.NOTIFY_WARN_AND_ABOVE,
                    includeSnippet = p?.get("includeSnippet") as? Boolean ?: false,
                    retainDays = (p?.get("retainDays") as? Number)?.toInt() ?: 30,
                    quietHours = QuietHours(
                        enabled = q?.get("enabled") as? Boolean ?: false,
                        start = q?.get("start") as? String ?: "21:00",
                        end = q?.get("end") as? String ?: "07:00",
                        utcOffsetMinutes = (q?.get("utcOffsetMinutes") as? Number)?.toInt() ?: 0
                    )
                )
            )
        }
        awaitClose { reg.remove() }
    }

    suspend fun savePrivacy(familyId: String, p: FamilyPrivacy) {
        Log.d("KGuard", "FamilyRepository: savePrivacy for familyId=$familyId")
        db.document("families/$familyId").update(
            "privacy", mapOf(
                "notifyOn" to p.notifyOn,
                "includeSnippet" to p.includeSnippet,
                "retainDays" to p.retainDays,
                "quietHours" to mapOf(
                    "enabled" to p.quietHours.enabled,
                    "start" to p.quietHours.start,
                    "end" to p.quietHours.end,
                    "utcOffsetMinutes" to p.quietHours.utcOffsetMinutes
                )
            )
        ).await()
        Log.d("KGuard", "FamilyRepository: savePrivacy success")
    }

    // ---------- push ----------
    /** Stops pushes reaching this phone after sign-out (the next account gets a fresh token). */
    suspend fun unregisterPushToken() {
        val uid = currentUser?.uid ?: return
        runCatching {
            val t = FirebaseMessaging.getInstance().token.await()
            db.document("users/$uid").update("fcmTokens", FieldValue.arrayRemove(t)).await()
        }.onFailure { Log.w("KGuard", "FamilyRepository: could not remove push token", it) }
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
    }

    suspend fun registerPushToken(token: String? = null) {
        val uid = currentUser?.uid ?: return
        val t = token ?: FirebaseMessaging.getInstance().token.await()
        try {
            // set(..., merge) creates the doc when missing; update() would fail NOT_FOUND.
            db.document("users/$uid")
                .set(mapOf("fcmTokens" to FieldValue.arrayUnion(t)), SetOptions.merge())
                .await()
            Log.d("KGuard", "FamilyRepository: registerPushToken success")
        } catch (e: Exception) {
            Log.e("KGuard", "FamilyRepository: registerPushToken failed", e)
            throw e
        }
    }
}

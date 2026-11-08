package com.kguard.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.kguard.app.data.model.AppAccessInfo
import com.kguard.app.data.model.AppApprovalStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * App approvals and per-app limits. Requests and decisions go through Cloud Functions so the parent and the
 * child's phone are both notified; the child's phone enforces the result (see KGuardCompanionService).
 * Screen time and device controls live in [ScreenTimeRepository].
 */
object ParentalControlsRepository {

    private val db get() = FirebaseFirestore.getInstance()
    private val functions get() = FirebaseFunctions.getInstance()

    /** Live list of a family's apps, newest request first; optionally for one child. */
    fun appsStream(familyId: String, childId: String? = null): Flow<List<AppAccessInfo>> = callbackFlow {
        var query: Query = db.collection("families/$familyId/apps")
        if (!childId.isNullOrBlank()) query = query.whereEqualTo("childId", childId)
        query = query.orderBy("requestedAt", Query.Direction.DESCENDING)

        val reg = query.addSnapshotListener { snap, err ->
            if (err != null) {
                android.util.Log.e("KGuard", "appsStream error", err)
                trySend(emptyList())
                return@addSnapshotListener
            }
            trySend(snap?.documents.orEmpty().mapNotNull { parseDoc(it.id, it.data) })
        }
        awaitClose { reg.remove() }
    }

    private fun parseDoc(docId: String, data: Map<String, Any?>?): AppAccessInfo? {
        if (data == null) return null
        return AppAccessInfo(
            id = docId,
            appName = data["appName"] as? String ?: (data["packageName"] as? String ?: "Unknown app"),
            packageName = data["packageName"] as? String ?: "",
            category = data["category"] as? String ?: "General",
            minAgeRating = (data["minAgeRating"] as? Number)?.toInt() ?: 0,
            safetyScore = (data["safetyScore"] as? Number)?.toInt() ?: 0,
            approvalStatus = AppApprovalStatus.fromString(data["approvalStatus"] as? String),
            dailyLimitMinutes = (data["dailyLimitMinutes"] as? Number)?.toInt() ?: 0,
            safetyReason = data["safetyReason"] as? String ?: "",
            childId = data["childId"] as? String ?: "",
            childName = data["childName"] as? String ?: "",
            childLinkedUid = data["childLinkedUid"] as? String,
            requestedAt = data["requestedAt"] as? Timestamp,
            reviewedAt = data["reviewedAt"] as? Timestamp,
            reviewedBy = data["reviewedBy"] as? String,
            installed = data["installed"] as? Boolean ?: true,
            uninstalledAt = data["uninstalledAt"] as? Timestamp,
            source = data["source"] as? String
        )
    }

    suspend fun requestAppApproval(
        familyId: String,
        childId: String,
        appName: String,
        packageName: String,
        category: String = "General",
        minAgeRating: Int = 3,
        dailyLimitMinutes: Int = 0
    ): Map<String, Any?> {
        val res = functions.getHttpsCallable("requestAppApproval").call(
            mapOf(
                "childId" to childId,
                "appName" to appName,
                "packageName" to packageName,
                "category" to category,
                "minAgeRating" to minAgeRating,
                "dailyLimitMinutes" to dailyLimitMinutes
            )
        ).await()
        @Suppress("UNCHECKED_CAST")
        return res.data as Map<String, Any?>
    }

    /** Through the function only, so the child's phone is told at once. Errors reach the caller. */
    suspend fun respondToAppApproval(
        @Suppress("UNUSED_PARAMETER") familyId: String,
        appId: String,
        decision: AppApprovalStatus,
        dailyLimitMinutes: Int? = null
    ) {
        functions.getHttpsCallable("respondToAppApproval").call(
            mapOf("appId" to appId, "decision" to decision.name, "dailyLimitMinutes" to dailyLimitMinutes)
        ).await()
    }

    /** 0 removes the per-app limit. The child's phone picks the change up within a second. */
    suspend fun updateAppLimit(familyId: String, appId: String, limitMinutes: Int) {
        db.document("families/$familyId/apps/$appId").update("dailyLimitMinutes", limitMinutes.coerceIn(0, 1440)).await()
    }
}

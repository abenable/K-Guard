package com.kguard.app.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.AppUsageItem
import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.model.DeviceLockState
import com.kguard.app.data.model.RemoteAction
import com.kguard.app.data.model.RemoteCommand
import com.kguard.app.data.model.ScreenLock
import com.kguard.app.data.model.ScreenTimeConfig
import com.kguard.app.screentime.ScreenPolicy
import com.kguard.app.screentime.ScreenPolicy.Companion.F
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Parent-side access to one child's screen time and device controls.
 *
 * The parent writes only rule fields ([ScreenPolicy]) to `families/{familyId}/devices/{childId}`; the
 * child's phone writes only telemetry there and enforces the rules, so a change applies on the child's
 * phone within a second while it is online, and as soon as it reconnects otherwise. Every action is also
 * logged in `families/{familyId}/commands`, which the child's phone marks as executed: that is the
 * parent's delivery receipt.
 */
object ScreenTimeRepository {
    private const val TAG = "KGuardScreenTime"
    private val db get() = FirebaseFirestore.getInstance()
    private fun deviceRef(familyId: String, childId: String) = db.document("families/$familyId/devices/$childId")

    /** A phone that has not reported for this long is shown as offline. */
    private const val ONLINE_WINDOW_MS = 3 * 60_000L

    fun device(familyId: String, childId: String, childName: String): Flow<ChildDevice> = callbackFlow {
        val reg = deviceRef(familyId, childId).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e(TAG, "device listener error", err)
                trySend(ChildDevice(childId = childId, childName = childName))
                return@addSnapshotListener
            }
            trySend(parseDevice(childId, childName, snap?.data))
        }
        awaitClose { reg.remove() }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseDevice(childId: String, childName: String, data: Map<String, Any?>?, nowMillis: Long = System.currentTimeMillis()): ChildDevice {
        val policy = ScreenPolicy.parse(data)
        if (data == null) return ChildDevice(childId = childId, childName = childName)
        val lastSynced = (data["lastSyncedAt"] as? Timestamp)?.toDate()?.time
        val today = data["usageDate"] as? String
        val lockState = if (lastSynced == null) DeviceLockState.ACTIVE_UNLOCKED
            else DeviceLockState.entries.find { it.id == data["lockState"] as? String } ?: DeviceLockState.ACTIVE_UNLOCKED
        val apps = (data["appUsages"] as? List<Map<String, Any?>>).orEmpty().map { m ->
            AppUsageItem(
                packageName = m["packageName"] as? String ?: "",
                appName = m["appName"] as? String ?: "",
                category = m["category"] as? String ?: "General",
                usedMinutes = (m["usedMinutes"] as? Number)?.toInt() ?: 0,
                dailyLimitMinutes = (m["dailyLimitMinutes"] as? Number)?.toInt()?.takeIf { it > 0 },
                isBlocked = m["isBlocked"] as? Boolean ?: false,
                isCurrentlyActive = m["isCurrentlyActive"] as? Boolean ?: false
            )
        }
        val lockUntil = (data["lockUntil"] as? Number)?.toLong()
        return ChildDevice(
            deviceId = childId,
            childId = childId,
            childName = data["childName"] as? String ?: childName,
            deviceName = data["deviceName"] as? String ?: "",
            deviceModel = data["deviceModel"] as? String ?: "",
            androidVersion = data["androidVersion"] as? String ?: "",
            isOnline = lastSynced != null && nowMillis - lastSynced < ONLINE_WINDOW_MS,
            lastSeenMinutesAgo = lastSynced?.let { ((nowMillis - it) / 60_000L).toInt().coerceAtLeast(0) } ?: -1,
            lastSyncedAtMillis = lastSynced,
            batteryPercent = (data["batteryPercent"] as? Number)?.toInt() ?: -1,
            isCharging = data["isCharging"] as? Boolean ?: false,
            networkName = data["networkName"] as? String ?: "",
            isDeviceAdminActive = data["isDeviceAdminActive"] as? Boolean ?: false,
            currentForegroundApp = data["currentForegroundApp"] as? String,
            lockState = lockState,
            lockReason = if (lastSynced == null) null else data["lockReason"] as? String,
            lockUntilMillis = lockUntil,
            screenLock = ScreenLock(isLocked = policy.remoteLock.activeAt(nowMillis) || (policy.remoteLock.fromMillis ?: 0) > nowMillis, lockReason = policy.remoteLock.reason),
            usedScreenTimeMinutes = (data["usedScreenTimeMinutes"] as? Number)?.toInt() ?: 0,
            usageDate = today,
            dailyLimitMinutes = policy.dailyLimitMinutes,
            bonusMinutesToday = today?.let { policy.bonusFor(it) } ?: 0,
            pickupsCount = (data["pickupsCount"] as? Number)?.toInt() ?: 0,
            bedtimeStart = policy.bedtimeStart,
            bedtimeEnd = policy.bedtimeEnd,
            bedtimeEnabled = policy.bedtimeEnabled,
            schoolStart = policy.schoolStart,
            schoolEnd = policy.schoolEnd,
            schoolEnabled = policy.schoolEnabled,
            remoteLockUntilMillis = policy.remoteLock.untilMillis,
            overrideUntilMillis = policy.overrideUntilMillis,
            policyInitialized = policy.initialized,
            permissions = (data["permissions"] as? Map<String, Any?>).orEmpty().mapValues { it.value as? Boolean ?: false },
            appUsages = apps,
            hourlyUsageMinutes = (data["hourlyUsageMinutes"] as? List<*>)?.map { (it as? Number)?.toInt() ?: 0 } ?: emptyList()
        )
    }

    fun commands(familyId: String, childId: String): Flow<List<RemoteCommand>> = callbackFlow {
        val reg = db.collection("families/$familyId/commands")
            .whereEqualTo("childId", childId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "commands listener error", err)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snap?.documents.orEmpty().mapNotNull { d ->
                    RemoteCommand(
                        id = d.id,
                        childId = d.getString("childId") ?: childId,
                        childName = d.getString("childName") ?: "",
                        action = RemoteAction.entries.find { it.id == d.getString("action") } ?: return@mapNotNull null,
                        description = d.getString("description") ?: "",
                        reason = d.getString("reason")?.takeIf { it.isNotBlank() },
                        timestamp = d.getLong("timestamp") ?: 0L,
                        status = when (d.getString("status")) {
                            "pending" -> "Sent · waiting for the phone"
                            "executed" -> "Delivered"
                            "expired" -> "Not delivered · phone was offline"
                            else -> d.getString("status") ?: ""
                        }
                    )
                })
            }
        awaitClose { reg.remove() }
    }

    /** Installs and uninstalls on the child's phone, newest first (written by Cloud Functions). */
    fun appActivity(familyId: String, childId: String): Flow<List<AppActivity>> = callbackFlow {
        val reg = db.collection("families/$familyId/appActivity")
            .whereEqualTo("childId", childId)
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "app activity listener error", err)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(snap?.documents.orEmpty().map { d ->
                    AppActivity(
                        id = d.id,
                        type = d.getString("type") ?: "",
                        appName = d.getString("appName") ?: d.getString("packageName") ?: "",
                        packageName = d.getString("packageName") ?: "",
                        status = d.getString("approvalStatus"),
                        atMillis = d.getTimestamp("at")?.toDate()?.time
                    )
                })
            }
        awaitClose { reg.remove() }
    }

    // ---------------------------------------------------------------- writes
    private suspend fun apply(
        familyId: String, childId: String, childName: String,
        action: RemoteAction, description: String, reason: String? = null,
        rules: Map<String, Any?> = emptyMap()
    ) {
        val batch = db.batch()
        if (rules.isNotEmpty()) batch.set(deviceRef(familyId, childId), rules + (F.INITIALIZED to true), SetOptions.merge())
        batch.set(
            db.collection("families/$familyId/commands").document(),
            mapOf(
                "childId" to childId,
                "childName" to childName,
                "action" to action.id,
                "description" to description,
                "reason" to (reason ?: ""),
                "timestamp" to System.currentTimeMillis(),
                "sentBy" to FirebaseAuth.getInstance().currentUser?.uid,
                "status" to "pending"
            )
        )
        batch.commit().await()
    }

    /**
     * Locks the whole phone. [minutes] null means until the parent unlocks it; [startsInMinutes] schedules
     * the lock to begin later (the child gets warnings before it starts).
     */
    suspend fun lock(familyId: String, childId: String, childName: String, reason: String, minutes: Int?, startsInMinutes: Int? = null) {
        val now = System.currentTimeMillis()
        val from = startsInMinutes?.takeIf { it > 0 }?.let { now + it * 60_000L }
        val until = minutes?.takeIf { it > 0 }?.let { (from ?: now) + it * 60_000L }
        val desc = when {
            from != null -> "Phone will lock in ${formatDuration(startsInMinutes)}"
            until != null -> "Locked the phone for ${formatDuration(minutes)}"
            else -> "Locked the phone"
        }
        apply(
            familyId, childId, childName, RemoteAction.SWITCH_OFF_LOCK, desc, reason,
            mapOf(F.REMOTE_LOCK to mapOf("locked" to true, "reason" to reason.trim().take(140), "until" to until, "from" to from))
        )
    }

    /**
     * Unlocks the phone. When bedtime or the daily limit is what locked it, they are paused for
     * [pauseSchedulesMinutes]; afterwards the normal rules apply again.
     */
    suspend fun unlock(familyId: String, childId: String, childName: String, pauseSchedulesMinutes: Int) {
        val rules = mutableMapOf<String, Any?>(F.REMOTE_LOCK to mapOf("locked" to false, "reason" to null, "until" to null))
        if (pauseSchedulesMinutes > 0) rules[F.OVERRIDE_UNTIL] = System.currentTimeMillis() + pauseSchedulesMinutes * 60_000L
        val desc = if (pauseSchedulesMinutes > 0) "Unlocked the phone for ${formatDuration(pauseSchedulesMinutes)}" else "Unlocked the phone"
        apply(familyId, childId, childName, RemoteAction.UNLOCK_DEVICE, desc, rules = rules)
    }

    suspend fun soundAlarm(familyId: String, childId: String, childName: String) =
        apply(familyId, childId, childName, RemoteAction.SOUND_ALARM, "Played an alarm on the phone")

    /** Adds minutes for the child's current day only; they do not carry over to tomorrow. */
    suspend fun addBonus(familyId: String, childId: String, childName: String, minutes: Int, childToday: String) {
        val ref = deviceRef(familyId, childId)
        db.runTransaction { tx ->
            val p = ScreenPolicy.parse(tx.get(ref).data)
            val current = p.bonusFor(childToday)
            tx.set(ref, mapOf(F.BONUS to (current + minutes).coerceAtMost(720), F.BONUS_DATE to childToday, F.INITIALIZED to true), SetOptions.merge())
        }.await()
        apply(familyId, childId, childName, RemoteAction.ADD_BONUS_TIME, "Added ${formatDuration(minutes)} of screen time for today")
    }

    suspend fun setDailyLimit(familyId: String, childId: String, childName: String, minutes: Int) {
        val m = minutes.coerceIn(0, 1440)
        apply(
            familyId, childId, childName, RemoteAction.SET_DAILY_LIMIT,
            if (m == 0) "Removed the daily limit" else "Set the daily limit to ${formatDuration(m)}",
            rules = mapOf(F.DAILY_LIMIT to m)
        )
    }

    suspend fun setBedtime(familyId: String, childId: String, childName: String, start: String, end: String, enabled: Boolean) {
        require(ScreenPolicy.parseHm(start) != null && ScreenPolicy.parseHm(end) != null) { "Use times like 21:00." }
        apply(
            familyId, childId, childName, RemoteAction.TOGGLE_BEDTIME,
            if (enabled) "Bedtime set to $start–$end" else "Turned bedtime off",
            rules = mapOf(F.BED_START to start, F.BED_END to end, F.BED_ON to enabled)
        )
    }

    suspend fun setSchoolHours(familyId: String, childId: String, childName: String, start: String, end: String, enabled: Boolean) {
        require(ScreenPolicy.parseHm(start) != null && ScreenPolicy.parseHm(end) != null) { "Use times like 08:00." }
        apply(
            familyId, childId, childName, RemoteAction.TOGGLE_SCHOOL_MODE,
            if (enabled) "School hours set to $start–$end on weekdays" else "Turned school hours off",
            rules = mapOf(F.SCHOOL_START to start, F.SCHOOL_END to end, F.SCHOOL_ON to enabled)
        )
    }

    /**
     * First time a parent opens screen time for a child: write age-appropriate starting rules, so the parent
     * sees exactly what the phone enforces. Never overwrites rules a parent has already set.
     */
    suspend fun initializeDefaults(familyId: String, childId: String, profile: AgeProfile) {
        val ref = deviceRef(familyId, childId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (ScreenPolicy.parse(snap.data).initialized) return@runTransaction
            tx.set(
                ref,
                mapOf(
                    F.DAILY_LIMIT to ScreenTimeConfig.getDefaultLimitForAge(profile),
                    F.BED_ON to (profile != AgeProfile.ADULTS_18_PLUS),
                    F.BED_START to if (profile == AgeProfile.KIDS_3_7) "19:30" else if (profile == AgeProfile.PRETEENS_8_12) "20:30" else "22:00",
                    F.BED_END to "07:00",
                    F.SCHOOL_ON to false,
                    F.SCHOOL_START to "08:00",
                    F.SCHOOL_END to "15:00",
                    F.INITIALIZED to true,
                    "policyInitializedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }.await()
    }

    fun formatDuration(minutes: Int?): String {
        val m = minutes ?: 0
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "$r min"
            r == 0 -> if (h == 1) "1 hour" else "$h hours"
            else -> "${h}h ${r}m"
        }
    }
}

/** One install or uninstall on a child's phone. [type] is "installed" or "uninstalled". */
data class AppActivity(
    val id: String,
    val type: String,
    val appName: String,
    val packageName: String,
    val status: String?,
    val atMillis: Long?
)

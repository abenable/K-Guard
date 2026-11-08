package com.kguard.app.data.model

import kotlinx.serialization.Serializable

/**
 * State of lock or restrictions applied to a child's device.
 */
@Serializable
enum class DeviceLockState(val id: String, val label: String) {
    ACTIVE_UNLOCKED("active_unlocked", "Active & Unlocked"),
    REMOTE_LOCKED("remote_locked", "Screen Turned Off & Locked"),
    BEDTIME_LOCKED("bedtime_locked", "Bedtime Lockdown"),
    SCHOOL_LOCKED("school_locked", "School Focus Mode"),
    LIMIT_REACHED("limit_reached", "Daily Limit Reached")
}

/**
 * Type of remote action sent by parent to child phone.
 */
@Serializable
enum class RemoteAction(val id: String, val label: String) {
    SWITCH_OFF_LOCK("switch_off_lock", "Turn Off Screen & Lock"),
    UNLOCK_DEVICE("unlock_device", "Unlock & Resume Device"),
    SOUND_ALARM("sound_alarm", "Play Loud Ring Alert"),
    START_LIVE("start_live", "Start a Live Screen Check"),
    ADD_BONUS_TIME("add_bonus_time", "Add Bonus Screen Time"),
    SET_DAILY_LIMIT("set_daily_limit", "Set Daily Limit"),
    TOGGLE_BEDTIME("toggle_bedtime", "Toggle Bedtime Schedule"),
    TOGGLE_SCHOOL_MODE("toggle_school_mode", "Toggle School Mode")
}

/**
 * Detailed application usage record for today on the child's phone.
 */
@Serializable
data class AppUsageItem(
    val packageName: String = "",
    val appName: String = "",
    val category: String = "Entertainment",
    val usedMinutes: Int = 0,
    val dailyLimitMinutes: Int? = null,
    val isBlocked: Boolean = false,
    val isCurrentlyActive: Boolean = false
)

/**
 * Status of a child's phone as the parent sees it: rules the parent set, plus what the phone last reported.
 * Defaults mean "not reported yet", never invented values; check [hasReported] before showing telemetry.
 */
@Serializable
data class ChildDevice(
    val deviceId: String = "",
    val childId: String = "",
    val childName: String = "",
    val deviceName: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
    /** The phone reported within the last few minutes. */
    val isOnline: Boolean = false,
    /** -1 until the phone has reported. */
    val lastSeenMinutesAgo: Int = -1,
    val lastSyncedAtMillis: Long? = null,
    /** -1 when unknown. */
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val networkName: String = "",
    val isScreenOn: Boolean = false,
    val isDeviceAdminActive: Boolean = false,
    val currentForegroundApp: String? = null,
    /** The lock the phone is actually enforcing, as reported by the phone. */
    val lockState: DeviceLockState = DeviceLockState.ACTIVE_UNLOCKED,
    val lockedAtMillis: Long? = null,
    val lockReason: String? = null,
    val lockUntilMillis: Long? = null,
    val screenLock: ScreenLock = ScreenLock(),
    val usedScreenTimeMinutes: Int = 0,
    /** The child phone's date the usage figures belong to (yyyy-MM-dd). */
    val usageDate: String? = null,
    /** Daily limit set by the parent; 0 means no limit. */
    val dailyLimitMinutes: Int = 0,
    val bonusMinutesToday: Int = 0,
    val pickupsCount: Int = 0,
    val bedtimeStart: String = "21:00",
    val bedtimeEnd: String = "07:00",
    val bedtimeEnabled: Boolean = false,
    val schoolStart: String = "08:00",
    val schoolEnd: String = "15:00",
    val schoolEnabled: Boolean = false,
    /** A timed remote lock ends at this time. */
    val remoteLockUntilMillis: Long? = null,
    /** Bedtime and the daily limit are paused by the parent until this time. */
    val overrideUntilMillis: Long? = null,
    val policyInitialized: Boolean = false,
    /** usageAccess, overlay, deviceAdmin, notifications, batteryUnrestricted. */
    val permissions: Map<String, Boolean> = emptyMap(),
    val appUsages: List<AppUsageItem> = emptyList(),
    /** Minutes of use in each hour of the child's day (24 values). */
    val hourlyUsageMinutes: List<Int> = emptyList()
) {
    val hasReported: Boolean get() = lastSyncedAtMillis != null

    /** Today's allowance including bonus time; 0 means no limit. */
    val effectiveLimitMinutes: Int
        get() = if (dailyLimitMinutes <= 0) 0 else dailyLimitMinutes + bonusMinutesToday

    val remainingMinutes: Int
        get() = if (effectiveLimitMinutes <= 0) 0 else (effectiveLimitMinutes - usedScreenTimeMinutes).coerceAtLeast(0)

    val usageProgress: Float
        get() = if (effectiveLimitMinutes <= 0) 0f else (usedScreenTimeMinutes.toFloat() / effectiveLimitMinutes).coerceIn(0f, 1f)

    val isLocked: Boolean
        get() = lockState != DeviceLockState.ACTIVE_UNLOCKED

    /** Permissions the phone still needs for full protection, in plain words. */
    val missingProtections: List<String>
        get() = if (!hasReported) emptyList() else buildList {
            if (permissions["usageAccess"] == false) add("Usage access (needed to measure screen time and see apps)")
            if (permissions["overlay"] == false) add("Display over other apps (needed to lock the screen)")
            if (permissions["deviceAdmin"] == false) add("Device admin (switches the screen off when locking)")
            if (permissions["notifications"] == false) add("Notifications (warnings before bedtime and limits)")
            if (permissions["batteryUnrestricted"] == false) add("Unrestricted battery (stops Android pausing protection)")
            if (permissions["alwaysOnVpn"] == false) add("Always-on VPN (Android keeps web protection running and no other VPN can replace it)")
        }
}

/**
 * Audit log entry for a remote command sent by the parent.
 */
@Serializable
data class RemoteCommand(
    val id: String = "",
    val childId: String = "",
    val childName: String = "",
    val deviceId: String = "",
    val action: RemoteAction = RemoteAction.SWITCH_OFF_LOCK,
    val description: String = "",
    val reason: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "Delivered & Applied"
)


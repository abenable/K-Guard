package com.kguard.app.screentime

import java.util.Calendar

/**
 * A parent's remote lock. [untilMillis] null means "until I unlock it"; otherwise it ends by itself.
 */
data class RemoteLock(
    val locked: Boolean = false,
    val reason: String? = null,
    val untilMillis: Long? = null,
    /** A lock scheduled to start later ("lock after 30 minutes"); null means it starts now. */
    val fromMillis: Long? = null
) {
    fun activeAt(now: Long): Boolean =
        locked && (fromMillis == null || now >= fromMillis) && (untilMillis == null || now < untilMillis)
}

/**
 * Screen time rules for one child, written by the parent to `families/{familyId}/devices/{childId}` and
 * enforced by the child's phone. The parent's phone and the child's phone parse the same fields with the
 * same defaults, so what the parent sees is what the phone enforces.
 *
 * A missing document means no rules. The parent's screen writes age-based defaults the first time it opens.
 */
data class ScreenPolicy(
    /** 0 means no daily limit. */
    val dailyLimitMinutes: Int = 0,
    /** Extra minutes granted for [bonusDate] only (the child phone's local date, yyyy-MM-dd). */
    val bonusMinutes: Int = 0,
    val bonusDate: String? = null,
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: String = "21:00",
    val bedtimeEnd: String = "07:00",
    val schoolEnabled: Boolean = false,
    val schoolStart: String = "08:00",
    val schoolEnd: String = "15:00",
    /** [Calendar.DAY_OF_WEEK] values on which school hours apply. */
    val schoolDays: Set<Int> = WEEKDAYS,
    val remoteLock: RemoteLock = RemoteLock(),
    /** A parent's "unlock now": bedtime and the daily limit are paused until this time. */
    val overrideUntilMillis: Long? = null,
    /** True once a parent (or the defaults) has written rules for this child. */
    val initialized: Boolean = false
) {
    fun limitFor(today: String): Int =
        if (dailyLimitMinutes <= 0) 0 else dailyLimitMinutes + (if (bonusDate == today) bonusMinutes.coerceAtLeast(0) else 0)

    fun bonusFor(today: String): Int = if (bonusDate == today) bonusMinutes.coerceAtLeast(0) else 0

    companion object {
        val WEEKDAYS = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)

        /** Field names in the device document. Only the parent writes these. */
        object F {
            const val DAILY_LIMIT = "dailyLimitMinutes"
            const val BONUS = "bonusMinutes"
            const val BONUS_DATE = "bonusDate"
            const val BED_ON = "bedtimeEnabled"
            const val BED_START = "bedtimeStart"
            const val BED_END = "bedtimeEnd"
            const val SCHOOL_ON = "schoolEnabled"
            const val SCHOOL_START = "schoolStart"
            const val SCHOOL_END = "schoolEnd"
            const val SCHOOL_DAYS = "schoolDays"
            const val REMOTE_LOCK = "remoteLock"
            const val OVERRIDE_UNTIL = "overrideUntil"
            const val INITIALIZED = "policyInitialized"
        }

        @Suppress("UNCHECKED_CAST")
        fun parse(data: Map<String, Any?>?): ScreenPolicy {
            if (data == null) return ScreenPolicy()
            val lock = data[F.REMOTE_LOCK] as? Map<String, Any?>
            return ScreenPolicy(
                dailyLimitMinutes = (data[F.DAILY_LIMIT] as? Number)?.toInt()?.coerceIn(0, 1440) ?: 0,
                bonusMinutes = (data[F.BONUS] as? Number)?.toInt()?.coerceIn(0, 1440) ?: 0,
                bonusDate = data[F.BONUS_DATE] as? String,
                bedtimeEnabled = data[F.BED_ON] as? Boolean ?: false,
                bedtimeStart = (data[F.BED_START] as? String)?.takeIf { parseHm(it) != null } ?: "21:00",
                bedtimeEnd = (data[F.BED_END] as? String)?.takeIf { parseHm(it) != null } ?: "07:00",
                schoolEnabled = data[F.SCHOOL_ON] as? Boolean ?: false,
                schoolStart = (data[F.SCHOOL_START] as? String)?.takeIf { parseHm(it) != null } ?: "08:00",
                schoolEnd = (data[F.SCHOOL_END] as? String)?.takeIf { parseHm(it) != null } ?: "15:00",
                schoolDays = (data[F.SCHOOL_DAYS] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.filter { it in 1..7 }?.toSet() ?: WEEKDAYS,
                remoteLock = RemoteLock(
                    locked = lock?.get("locked") as? Boolean ?: false,
                    reason = (lock?.get("reason") as? String)?.takeIf { it.isNotBlank() },
                    untilMillis = (lock?.get("until") as? Number)?.toLong(),
                    fromMillis = (lock?.get("from") as? Number)?.toLong()
                ),
                overrideUntilMillis = (data[F.OVERRIDE_UNTIL] as? Number)?.toLong(),
                initialized = data[F.INITIALIZED] as? Boolean ?: false
            )
        }

        /** "21:30" -> 1290 minutes after midnight; null if malformed. */
        fun parseHm(s: String): Int? {
            val p = s.trim().split(":")
            if (p.size != 2) return null
            val h = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) h * 60 + m else null
        }

        /** True when [minute] falls in [start, end); windows may cross midnight (21:00-07:00). Equal ends = never. */
        fun inWindow(minute: Int, start: String, end: String): Boolean {
            val s = parseHm(start) ?: return false
            val e = parseHm(end) ?: return false
            return when {
                s == e -> false
                s < e -> minute in s until e
                else -> minute >= s || minute < e
            }
        }

        /** Minutes from [minute] until [hm] next occurs (0..1439). */
        fun minutesUntil(minute: Int, hm: String): Int? {
            val t = parseHm(hm) ?: return null
            return ((t - minute) % 1440 + 1440) % 1440
        }
    }
}

enum class LockKind { NONE, REMOTE, BEDTIME, LIMIT }

/** What the child's phone must do right now, and why. Shown to the child and reported to the parent. */
data class LockDecision(
    val kind: LockKind,
    val reason: String = "",
    /** When the lock ends by itself; null for "until a parent unlocks". */
    val untilMillis: Long? = null
) {
    val locked: Boolean get() = kind != LockKind.NONE

    /** Value of `lockState` reported to Firestore; matches [com.kguard.app.data.model.DeviceLockState] ids. */
    val stateId: String
        get() = when (kind) {
            LockKind.NONE -> "active_unlocked"
            LockKind.REMOTE -> "remote_locked"
            LockKind.BEDTIME -> "bedtime_locked"
            LockKind.LIMIT -> "limit_reached"
        }
}

/** The local clock reading the evaluator works from, so the rules can be tested without a device. */
data class LocalClock(
    val nowMillis: Long,
    /** Minutes after local midnight. */
    val minuteOfDay: Int,
    /** [Calendar.DAY_OF_WEEK]. */
    val dayOfWeek: Int,
    /** Local date, yyyy-MM-dd. */
    val today: String,
    /** Epoch millis of the next local midnight. */
    val nextMidnightMillis: Long
) {
    companion object {
        fun now(): LocalClock {
            val c = Calendar.getInstance()
            val now = c.timeInMillis
            val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val dow = c.get(Calendar.DAY_OF_WEEK)
            val today = String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return LocalClock(now, minute, dow, today, c.timeInMillis)
        }
    }
}

/**
 * Decides whether the whole phone is locked. Order of precedence:
 * 1. A parent's remote lock (until it ends or the parent unlocks).
 * 2. A parent's "unlock now", which pauses bedtime and the daily limit until it ends.
 * 3. Bedtime.
 * 4. The daily limit, including today's bonus time.
 *
 * School hours do not lock the phone; they block distracting apps (see [AppRules]).
 */
object LockEvaluator {

    fun evaluate(policy: ScreenPolicy, clock: LocalClock, usedMinutesToday: Int): LockDecision {
        val r = policy.remoteLock
        if (r.activeAt(clock.nowMillis)) {
            return LockDecision(LockKind.REMOTE, r.reason ?: "Your parent has locked this phone.", r.untilMillis)
        }

        val overridden = policy.overrideUntilMillis?.let { clock.nowMillis < it } == true
        if (overridden) return LockDecision(LockKind.NONE)

        if (policy.bedtimeEnabled && ScreenPolicy.inWindow(clock.minuteOfDay, policy.bedtimeStart, policy.bedtimeEnd)) {
            val left = ScreenPolicy.minutesUntil(clock.minuteOfDay, policy.bedtimeEnd) ?: 0
            return LockDecision(
                LockKind.BEDTIME,
                "It's bedtime. The phone unlocks at ${policy.bedtimeEnd}.",
                minuteAligned(clock.nowMillis) + left * 60_000L
            )
        }

        val limit = policy.limitFor(clock.today)
        if (limit > 0 && usedMinutesToday >= limit) {
            return LockDecision(
                LockKind.LIMIT,
                "You've used today's ${formatMinutes(limit)} of screen time. It resets at midnight.",
                clock.nextMidnightMillis
            )
        }
        return LockDecision(LockKind.NONE)
    }

    fun isSchoolTime(policy: ScreenPolicy, clock: LocalClock): Boolean {
        if (!policy.schoolEnabled || clock.dayOfWeek !in policy.schoolDays) return false
        if (policy.overrideUntilMillis?.let { clock.nowMillis < it } == true) return false
        return ScreenPolicy.inWindow(clock.minuteOfDay, policy.schoolStart, policy.schoolEnd)
    }

    /** Minutes until bedtime starts, or null if bedtime is off or already running. */
    fun minutesToBedtime(policy: ScreenPolicy, clock: LocalClock): Int? {
        if (!policy.bedtimeEnabled) return null
        if (ScreenPolicy.inWindow(clock.minuteOfDay, policy.bedtimeStart, policy.bedtimeEnd)) return null
        return ScreenPolicy.minutesUntil(clock.minuteOfDay, policy.bedtimeStart)
    }

    private fun minuteAligned(millis: Long) = millis - millis % 60_000L

    fun formatMinutes(m: Int): String {
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "${r}m"
            r == 0 -> "${h}h"
            else -> "${h}h ${r}m"
        }
    }
}

/** Per-app decisions on the child's phone. */
object AppRules {
    /** Categories paused during school hours. Calls, messages, maps, education and K-Guard stay usable. */
    const val CAT_GAME = 0      // ApplicationInfo.CATEGORY_GAME
    const val CAT_AUDIO = 1     // ApplicationInfo.CATEGORY_AUDIO
    const val CAT_VIDEO = 2     // ApplicationInfo.CATEGORY_VIDEO
    const val CAT_SOCIAL = 4    // ApplicationInfo.CATEGORY_SOCIAL
    val SCHOOL_PAUSED_CATEGORIES = setOf(CAT_GAME, CAT_VIDEO, CAT_SOCIAL, CAT_AUDIO)

    data class AppPolicy(val status: String, val dailyLimitMinutes: Int, val appName: String)

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Blocked(val title: String, val message: String) : Verdict
    }

    fun check(
        appName: String,
        policy: AppPolicy?,
        androidCategory: Int,
        usedMinutesToday: Int,
        schoolTime: Boolean
    ): Verdict {
        when (policy?.status) {
            "PENDING_REVIEW" -> return Verdict.Blocked("Waiting for your parent", "$appName needs your parent's approval before you can use it. They've been asked.")
            "REJECTED" -> return Verdict.Blocked("$appName isn't allowed", "Your parent hasn't approved $appName on this phone.")
        }
        val limit = policy?.dailyLimitMinutes ?: 0
        if (limit > 0 && usedMinutesToday >= limit) {
            return Verdict.Blocked("Time's up for $appName", "You've used today's ${LockEvaluator.formatMinutes(limit)} on $appName. Try again tomorrow.")
        }
        if (schoolTime && androidCategory in SCHOOL_PAUSED_CATEGORIES) {
            return Verdict.Blocked("School hours", "$appName is paused during school hours.")
        }
        return Verdict.Allowed
    }
}

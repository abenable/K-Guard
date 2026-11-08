package com.kguard.app.screentime

/**
 * Turns Android usage events into foreground time per app. Pure logic so it can be unit-tested.
 *
 * Only time an app spends on screen counts: the period from it coming to the foreground until it leaves,
 * the screen turns off, the keyguard shows, or the phone shuts down. The launcher and K-Guard itself are
 * excluded, so sitting on the home screen or on a lock screen never uses up a child's allowance.
 */
object UsageMath {
    // android.app.usage.UsageEvents.Event constants
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26

    data class Event(val packageName: String?, val type: Int, val timeMillis: Long)

    data class Result(
        /** Foreground milliseconds per package. */
        val perAppMillis: Map<String, Long>,
        /** Minutes of screen time in each local hour (24 entries). */
        val hourlyMinutes: List<Int>,
        /** Package in the foreground at [Result] time, or null (home screen, screen off, excluded app). */
        val currentPackage: String?,
        /** Times the phone was unlocked. */
        val unlocks: Int
    ) {
        val totalMinutes: Int get() = (perAppMillis.values.sum() / 60_000L).toInt()
        fun minutesFor(pkg: String): Int = ((perAppMillis[pkg] ?: 0L) / 60_000L).toInt()
    }

    /**
     * @param events events in time order from [dayStartMillis] to [nowMillis]
     * @param excluded packages that never count (launchers, K-Guard)
     * @param hourOf maps a timestamp to its local hour 0..23
     * @param screenOnNow whether the screen is on now; an app still open at [nowMillis] counts only if so
     */
    fun accumulate(
        events: List<Event>,
        dayStartMillis: Long,
        nowMillis: Long,
        excluded: Set<String>,
        hourOf: (Long) -> Int,
        screenOnNow: Boolean
    ): Result {
        val perApp = HashMap<String, Long>()
        val hourly = LongArray(24)
        var fg: String? = null
        var fgSince = 0L
        var unlocks = 0

        fun close(at: Long) {
            val pkg = fg ?: return
            val start = maxOf(fgSince, dayStartMillis)
            val end = minOf(at, nowMillis)
            if (end > start && pkg !in excluded) {
                perApp[pkg] = (perApp[pkg] ?: 0L) + (end - start)
                spread(start, end, hourly, hourOf)
            }
            fg = null
        }

        for (e in events) {
            when (e.type) {
                ACTIVITY_RESUMED -> {
                    val pkg = e.packageName ?: continue
                    if (pkg != fg) {
                        close(e.timeMillis)
                        fg = pkg
                        fgSince = e.timeMillis
                    }
                }
                ACTIVITY_PAUSED, ACTIVITY_STOPPED -> if (e.packageName != null && e.packageName == fg) close(e.timeMillis)
                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN -> close(e.timeMillis)
                KEYGUARD_HIDDEN -> if (e.timeMillis >= dayStartMillis) unlocks++
            }
        }
        val current = fg
        if (current != null && screenOnNow) close(nowMillis) else fg = null

        return Result(
            perAppMillis = perApp,
            hourlyMinutes = hourly.map { (it / 60_000L).toInt() },
            currentPackage = current?.takeIf { screenOnNow && it !in excluded },
            unlocks = unlocks
        )
    }

    /** Adds [start, end) to the hour buckets, splitting across hour boundaries. */
    private fun spread(start: Long, end: Long, hourly: LongArray, hourOf: (Long) -> Int) {
        var t = start
        while (t < end) {
            val nextHour = t - (t % 3_600_000L) + 3_600_000L
            val sliceEnd = minOf(end, nextHour)
            hourly[hourOf(t).coerceIn(0, 23)] += sliceEnd - t
            t = sliceEnd
        }
    }
}

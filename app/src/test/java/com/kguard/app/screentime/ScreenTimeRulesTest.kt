package com.kguard.app.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScreenTimeRulesTest {

    private fun clock(hm: String, today: String = "2026-11-06", dow: Int = Calendar.FRIDAY, now: Long = 1_000_000_000L) =
        LocalClock(now, ScreenPolicy.parseHm(hm)!!, dow, today, now + 10 * 3_600_000L)

    // ---------------------------------------------------------------- windows
    @Test fun windowCrossingMidnight() {
        assertTrue(ScreenPolicy.inWindow(ScreenPolicy.parseHm("22:00")!!, "21:00", "07:00"))
        assertTrue(ScreenPolicy.inWindow(ScreenPolicy.parseHm("03:00")!!, "21:00", "07:00"))
        assertFalse(ScreenPolicy.inWindow(ScreenPolicy.parseHm("07:00")!!, "21:00", "07:00"))
        assertFalse(ScreenPolicy.inWindow(ScreenPolicy.parseHm("12:00")!!, "21:00", "07:00"))
    }

    @Test fun sameDayWindowAndDegenerateWindow() {
        assertTrue(ScreenPolicy.inWindow(ScreenPolicy.parseHm("09:00")!!, "08:00", "15:00"))
        assertFalse(ScreenPolicy.inWindow(ScreenPolicy.parseHm("15:00")!!, "08:00", "15:00"))
        assertFalse(ScreenPolicy.inWindow(ScreenPolicy.parseHm("09:00")!!, "08:00", "08:00"))
        assertFalse(ScreenPolicy.inWindow(600, "bad", "15:00"))
    }

    // ---------------------------------------------------------------- whole-phone lock
    @Test fun noRulesMeansUnlocked() {
        assertEquals(LockKind.NONE, LockEvaluator.evaluate(ScreenPolicy(), clock("23:00"), 900).kind)
    }

    @Test fun bedtimeLocksUntilEnd() {
        val p = ScreenPolicy(bedtimeEnabled = true, bedtimeStart = "21:00", bedtimeEnd = "07:00")
        val d = LockEvaluator.evaluate(p, clock("23:00"), 0)
        assertEquals(LockKind.BEDTIME, d.kind)
        assertEquals("bedtime_locked", d.stateId)
        assertEquals(clock("23:00").nowMillis - clock("23:00").nowMillis % 60_000 + 8 * 3_600_000L, d.untilMillis)
        assertEquals(LockKind.NONE, LockEvaluator.evaluate(p, clock("07:00"), 0).kind)
    }

    @Test fun limitIncludesTodaysBonusOnly() {
        val p = ScreenPolicy(dailyLimitMinutes = 60, bonusMinutes = 30, bonusDate = "2026-11-06")
        assertEquals(LockKind.NONE, LockEvaluator.evaluate(p, clock("12:00"), 80).kind)
        assertEquals(LockKind.LIMIT, LockEvaluator.evaluate(p, clock("12:00"), 90).kind)
        // The next day the bonus no longer applies.
        assertEquals(LockKind.LIMIT, LockEvaluator.evaluate(p, clock("12:00", today = "2026-11-07"), 60).kind)
    }

    @Test fun raisingTheLimitUnlocks() {
        val used = 70
        assertEquals(LockKind.LIMIT, LockEvaluator.evaluate(ScreenPolicy(dailyLimitMinutes = 60), clock("12:00"), used).kind)
        assertEquals(LockKind.NONE, LockEvaluator.evaluate(ScreenPolicy(dailyLimitMinutes = 90), clock("12:00"), used).kind)
    }

    @Test fun remoteLockBeatsEverythingAndCanExpire() {
        val now = clock("12:00").nowMillis
        val p = ScreenPolicy(remoteLock = RemoteLock(true, "Homework first", now + 60_000), overrideUntilMillis = now + 3_600_000)
        val d = LockEvaluator.evaluate(p, clock("12:00"), 0)
        assertEquals(LockKind.REMOTE, d.kind)
        assertEquals("Homework first", d.reason)
        val later = clock("12:00", now = now + 61_000)
        assertEquals(LockKind.NONE, LockEvaluator.evaluate(p, later, 0).kind)
    }

    @Test fun parentUnlockPausesBedtimeAndLimitUntilItEnds() {
        val now = clock("22:00").nowMillis
        val p = ScreenPolicy(bedtimeEnabled = true, dailyLimitMinutes = 10, overrideUntilMillis = now + 30 * 60_000L)
        assertEquals(LockKind.NONE, LockEvaluator.evaluate(p, clock("22:00"), 500).kind)
        assertEquals(LockKind.BEDTIME, LockEvaluator.evaluate(p, clock("22:00", now = now + 31 * 60_000L), 500).kind)
    }

    @Test fun schoolHoursOnSchoolDaysOnly() {
        val p = ScreenPolicy(schoolEnabled = true, schoolStart = "08:00", schoolEnd = "15:00")
        assertTrue(LockEvaluator.isSchoolTime(p, clock("10:00", dow = Calendar.MONDAY)))
        assertFalse(LockEvaluator.isSchoolTime(p, clock("10:00", dow = Calendar.SATURDAY)))
        assertFalse(LockEvaluator.isSchoolTime(p, clock("16:00", dow = Calendar.MONDAY)))
    }

    @Test fun minutesToBedtime() {
        val p = ScreenPolicy(bedtimeEnabled = true, bedtimeStart = "21:00", bedtimeEnd = "07:00")
        assertEquals(10, LockEvaluator.minutesToBedtime(p, clock("20:50")))
        assertNull(LockEvaluator.minutesToBedtime(p, clock("21:10")))
    }

    // ---------------------------------------------------------------- per-app rules
    @Test fun pendingAndRejectedAppsAreBlocked() {
        assertTrue(AppRules.check("X", AppRules.AppPolicy("PENDING_REVIEW", 0, "X"), -1, 0, false) is AppRules.Verdict.Blocked)
        assertTrue(AppRules.check("X", AppRules.AppPolicy("REJECTED", 0, "X"), -1, 0, false) is AppRules.Verdict.Blocked)
        assertEquals(AppRules.Verdict.Allowed, AppRules.check("X", AppRules.AppPolicy("APPROVED", 0, "X"), -1, 0, false))
        assertEquals(AppRules.Verdict.Allowed, AppRules.check("X", null, -1, 0, false))
    }

    @Test fun perAppLimitAndSchoolCategories() {
        assertTrue(AppRules.check("G", AppRules.AppPolicy("APPROVED", 30, "G"), -1, 30, false) is AppRules.Verdict.Blocked)
        assertEquals(AppRules.Verdict.Allowed, AppRules.check("G", AppRules.AppPolicy("APPROVED", 30, "G"), -1, 29, false))
        assertTrue(AppRules.check("G", null, AppRules.CAT_GAME, 0, true) is AppRules.Verdict.Blocked)
        assertEquals(AppRules.Verdict.Allowed, AppRules.check("Maps", null, 6, 0, true))
    }

    @Test fun parseUsesSafeDefaults() {
        val p = ScreenPolicy.parse(mapOf("dailyLimitMinutes" to 5000, "bedtimeStart" to "25:00", "bedtimeEnabled" to true))
        assertEquals(1440, p.dailyLimitMinutes)
        assertEquals("21:00", p.bedtimeStart)
        assertTrue(p.bedtimeEnabled)
        assertFalse(p.remoteLock.locked)
    }

    // ---------------------------------------------------------------- usage maths
    private val hour: (Long) -> Int = { ((it / 3_600_000L) % 24).toInt() }

    @Test fun countsForegroundTimeAndExcludesLauncher() {
        val e = listOf(
            UsageMath.Event("launcher", UsageMath.ACTIVITY_RESUMED, 0),
            UsageMath.Event("game", UsageMath.ACTIVITY_RESUMED, 60_000),
            UsageMath.Event("game", UsageMath.ACTIVITY_PAUSED, 11 * 60_000),
            UsageMath.Event("launcher", UsageMath.ACTIVITY_RESUMED, 11 * 60_000),
            UsageMath.Event("video", UsageMath.ACTIVITY_RESUMED, 20 * 60_000)
        )
        val r = UsageMath.accumulate(e, 0, 25 * 60_000, setOf("launcher"), hour, screenOnNow = true)
        assertEquals(10, r.minutesFor("game"))
        assertEquals(5, r.minutesFor("video"))
        assertEquals(15, r.totalMinutes)
        assertEquals("video", r.currentPackage)
    }

    @Test fun screenOffStopsTheClock() {
        val e = listOf(
            UsageMath.Event("video", UsageMath.ACTIVITY_RESUMED, 0),
            UsageMath.Event(null, UsageMath.SCREEN_NON_INTERACTIVE, 5 * 60_000)
        )
        val r = UsageMath.accumulate(e, 0, 60 * 60_000, emptySet(), hour, screenOnNow = false)
        assertEquals(5, r.totalMinutes)
        assertNull(r.currentPackage)
    }

    @Test fun usageBeforeMidnightIsNotCountedToday() {
        val dayStart = 10 * 60_000L
        val e = listOf(UsageMath.Event("video", UsageMath.ACTIVITY_RESUMED, 0))
        val r = UsageMath.accumulate(e, dayStart, 30 * 60_000, emptySet(), hour, screenOnNow = true)
        assertEquals(20, r.totalMinutes)
    }

    @Test fun hourlyBucketsSplitAcrossHours() {
        val e = listOf(UsageMath.Event("video", UsageMath.ACTIVITY_RESUMED, 50 * 60_000L))
        val r = UsageMath.accumulate(e, 0, 70 * 60_000L, emptySet(), hour, screenOnNow = true)
        assertEquals(10, r.hourlyMinutes[0])
        assertEquals(10, r.hourlyMinutes[1])
    }

    @Test fun countsUnlocks() {
        val e = listOf(
            UsageMath.Event(null, UsageMath.KEYGUARD_HIDDEN, 1),
            UsageMath.Event(null, UsageMath.KEYGUARD_SHOWN, 2),
            UsageMath.Event(null, UsageMath.KEYGUARD_HIDDEN, 3)
        )
        assertEquals(2, UsageMath.accumulate(e, 0, 10, emptySet(), hour, true).unlocks)
    }
}

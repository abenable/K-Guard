package com.kguard.app.data.repository

import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.model.DeviceLockState
import com.kguard.app.data.repository.ScreenTimeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the parent's screen shows is parsed from the child's device document; nothing is invented. */
class ScreenTimeDeviceParseTest {

    @Test
    fun noDocumentMeansNothingReportedYet() {
        val d = ScreenTimeRepository.parseDevice("c1", "Ama", null)
        assertFalse(d.hasReported)
        assertFalse(d.isOnline)
        assertEquals(-1, d.batteryPercent)
        assertNull(d.currentForegroundApp)
        assertEquals(0, d.dailyLimitMinutes)
        assertFalse(d.bedtimeEnabled)
        assertTrue(d.missingProtections.isEmpty())
    }

    @Test
    fun parsesRulesAndReportedLock() {
        val d = ScreenTimeRepository.parseDevice(
            "c1", "Ama",
            mapOf(
                "dailyLimitMinutes" to 90,
                "bonusMinutes" to 30,
                "bonusDate" to "2026-11-06",
                "usageDate" to "2026-11-06",
                "usedScreenTimeMinutes" to 100,
                "bedtimeEnabled" to true,
                "bedtimeStart" to "20:30",
                "lockState" to "bedtime_locked",
                "lastSyncedAt" to com.google.firebase.Timestamp(java.util.Date()),
                "remoteLock" to mapOf("locked" to true, "reason" to "Dinner"),
                "permissions" to mapOf("overlay" to false, "usageAccess" to true)
            )
        )
        assertEquals(120, d.effectiveLimitMinutes)
        assertEquals(20, d.remainingMinutes)
        assertTrue(d.bedtimeEnabled)
        assertEquals("20:30", d.bedtimeStart)
        assertEquals(DeviceLockState.BEDTIME_LOCKED, d.lockState)
        assertTrue(d.screenLock.isLocked)
        assertEquals("Dinner", d.screenLock.lockReason)
    }

    @Test
    fun lockStateIsIgnoredUntilThePhoneHasReported() {
        // Left over from older builds that wrote lockState from the parent's side.
        val d = ScreenTimeRepository.parseDevice("c1", "Ama", mapOf("lockState" to "remote_locked", "lockReason" to "Bedtime / Sleep"))
        assertEquals(DeviceLockState.ACTIVE_UNLOCKED, d.lockState)
        assertNull(d.lockReason)
        assertFalse(d.isLocked)
    }

    @Test
    fun bonusFromAnotherDayDoesNotCount() {
        val d = ScreenTimeRepository.parseDevice(
            "c1", "Ama",
            mapOf("dailyLimitMinutes" to 60, "bonusMinutes" to 30, "bonusDate" to "2026-11-05", "usageDate" to "2026-11-06")
        )
        assertEquals(60, d.effectiveLimitMinutes)
    }

    @Test
    fun noLimitMeansNoProgress() {
        val d = ChildDevice(usedScreenTimeMinutes = 500)
        assertEquals(0f, d.usageProgress)
        assertEquals(0, d.remainingMinutes)
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("30 min", ScreenTimeRepository.formatDuration(30))
        assertEquals("1 hour", ScreenTimeRepository.formatDuration(60))
        assertEquals("1h 30m", ScreenTimeRepository.formatDuration(90))
    }
}

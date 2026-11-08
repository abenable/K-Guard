package com.kguard.app.viewmodel

import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.repository.ScreenTimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The repository is pure Firestore now (no demo layer), so JVM tests exercise the ViewModel's
 * honest-state behaviour: without a family everything is explicitly offline/empty and every
 * remote command asks to connect a device instead of pretending.
 */
class ScreenTimeViewModelTest {

    private lateinit var viewModel: ScreenTimeViewModel
    private val repository = ScreenTimeRepository
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        viewModel = ScreenTimeViewModel(repository, testScope)
    }

    @Test
    fun withoutAFamilyTheDeviceIsExplicitlyOffline() {
        val dev: ChildDevice = viewModel.currentDevice.value
        assertEquals("No device yet", dev.deviceName)
        assertFalse(dev.isOnline)
        assertFalse(dev.isLocked)
    }

    @Test
    fun withoutAFamilyCommandHistoryIsEmpty() {
        assertTrue(viewModel.commandHistory.value.isEmpty())
    }

    @Test
    fun remoteCommandsWithoutAFamilyAskToConnectInsteadOfFailing() {
        viewModel.remoteSwitchOff("c1", "Dinner Time")

        val feedback = viewModel.actionFeedback.value
        assertNotNull("A connect-first hint must be shown", feedback)
        assertTrue(feedback!!.message.contains("Connect the child's device first"))
        // Nothing was sent: still no fabricated telemetry or command log.
        assertTrue(viewModel.commandHistory.value.isEmpty())
        assertFalse(viewModel.currentDevice.value.isLocked)
    }

    @Test
    fun setFamilyAndChildRecordsTheSelection() {
        viewModel.setFamilyAndChild("fam1", "child-9", "Maya")
        assertEquals("child-9", viewModel.selectedChildId.value)
        assertEquals("Maya", viewModel.selectedChildName.value)
    }

    @Test
    fun selectChildRecordsTheSelection() {
        viewModel.selectChild("c2", "Kofi")
        assertEquals("c2", viewModel.selectedChildId.value)
        assertEquals("Kofi", viewModel.selectedChildName.value)
    }

    @Test
    fun feedbackIdsAlwaysIncreaseSoIdenticalMessagesRefire() {
        viewModel.remoteSwitchOff("c1", "Dinner Time")
        val first = viewModel.actionFeedback.value
        viewModel.clearFeedback()
        assertEquals(null, viewModel.actionFeedback.value)

        viewModel.remoteUnlock("c1")
        val second = viewModel.actionFeedback.value
        assertNotNull(second)
        assertTrue((second?.id ?: 0L) > (first?.id ?: 0L))
    }

    @Test
    fun ageRecommendationWithoutFamilyRoutesThroughConnectHint() {
        viewModel.applyAgeRecommendation("c1", com.kguard.app.data.model.AgeProfile.KIDS_3_7)
        assertNotNull(viewModel.actionFeedback.value)
        assertTrue(viewModel.actionFeedback.value!!.message.contains("Connect the child's device first"))
    }
}

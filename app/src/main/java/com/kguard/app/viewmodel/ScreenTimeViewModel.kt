package com.kguard.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.model.DeviceLockState
import com.kguard.app.data.model.RemoteCommand
import com.kguard.app.data.model.ScreenTimeConfig
import com.kguard.app.data.repository.AppActivity
import com.kguard.app.data.repository.ScreenTimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A one-shot UI feedback event. The id lets identical consecutive messages re-trigger snackbars. */
data class ScreenTimeFeedback(val id: Long, val message: String)

/**
 * Parent's phone: one child's screen time and device controls. Every action writes rules to Firestore
 * (see [ScreenTimeRepository]); the child's phone applies them and reports back, and [currentDevice] shows
 * what the phone actually reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTimeViewModel(
    private val repository: ScreenTimeRepository = ScreenTimeRepository,
    /** Optional scope so unit tests can drive the ViewModel on a synchronous dispatcher; defaults to [viewModelScope]. */
    private val externalScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope get() = externalScope ?: viewModelScope

    private val _familyId = MutableStateFlow<String?>(null)
    private val _selectedChildId = MutableStateFlow("")
    private val _selectedChildName = MutableStateFlow("")

    val selectedChildId: StateFlow<String> = _selectedChildId.asStateFlow()
    val selectedChildName: StateFlow<String> = _selectedChildName.asStateFlow()

    private val _actionFeedback = MutableStateFlow<ScreenTimeFeedback?>(null)
    val actionFeedback: StateFlow<ScreenTimeFeedback?> = _actionFeedback.asStateFlow()
    private var nextFeedbackId = 0L

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun noDevice(cid: String = "", name: String = "") =
        ChildDevice(childId = cid, childName = name, deviceName = "No device yet")

    val currentDevice: StateFlow<ChildDevice> = combine(_familyId, _selectedChildId, _selectedChildName) { f, c, n -> Triple(f, c, n) }
        .flatMapLatest { (fid, cid, name) ->
            // No family or child yet: an explicitly offline device, never fabricated telemetry.
            if (fid == null || cid.isBlank()) flowOf(noDevice(cid, name)) else repository.device(fid, cid, name)
        }
        .stateIn(scope, SharingStarted.Eagerly, noDevice())

    val commandHistory: StateFlow<List<RemoteCommand>> = combine(_familyId, _selectedChildId) { f, c -> f to c }
        .flatMapLatest { (fid, cid) -> if (fid == null || cid.isBlank()) flowOf(emptyList()) else repository.commands(fid, cid) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val appActivity: StateFlow<List<AppActivity>> = combine(_familyId, _selectedChildId) { f, c -> f to c }
        .flatMapLatest { (fid, cid) -> if (fid == null || cid.isBlank()) flowOf(emptyList()) else repository.appActivity(fid, cid) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun setFamilyAndChild(familyId: String?, childId: String, childName: String) {
        _familyId.value = familyId
        _selectedChildId.value = childId
        _selectedChildName.value = childName
    }

    fun selectChild(id: String, name: String) {
        _selectedChildId.value = id
        _selectedChildName.value = name
    }

    private fun showFeedback(message: String) {
        _actionFeedback.value = ScreenTimeFeedback(nextFeedbackId++, message)
    }

    fun clearFeedback() { _actionFeedback.value = null }

    /** Writes age-appropriate starting rules the first time a parent opens screen time for this child. */
    fun initializeDefaultsIfNeeded(profile: AgeProfile) {
        val fid = _familyId.value ?: return
        val cid = _selectedChildId.value.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            runCatching { repository.initializeDefaults(fid, cid, profile) }
                .onFailure { Log.e(TAG, "Could not write starting rules", it) }
        }
    }

    private fun act(success: String, block: suspend (fid: String, cid: String, name: String) -> Unit) {
        val fid = _familyId.value
        val cid = _selectedChildId.value
        if (fid == null) {
            showFeedback("Connect the child's device first — its family is not ready yet.")
            return
        }
        if (cid.isBlank()) { showFeedback("Choose a child first."); return }
        val name = _selectedChildName.value
        scope.launch {
            _busy.value = true
            try {
                block(fid, cid, name)
                showFeedback(if (currentDevice.value.isOnline) success else "$success It applies as soon as $name's phone is back online.")
            } catch (e: Exception) {
                Log.e(TAG, "Screen time action failed", e)
                showFeedback("Couldn't save that: ${e.localizedMessage ?: "check the connection and try again."}")
            } finally {
                _busy.value = false
            }
        }
    }

    // The childId parameters match the screens' existing calls; the selected child is always the target.

    fun remoteSwitchOff(@Suppress("UNUSED_PARAMETER") childId: String, reason: String) =
        act("${_selectedChildName.value}'s phone is locked.") { f, c, n -> repository.lock(f, c, n, reason.ifBlank { "Your parent has locked this phone." }, null) }

    /** Locks for [hours]h[minutes]m; [startsInMinutes] > 0 schedules the lock to begin later. */
    fun remoteTimedLock(@Suppress("UNUSED_PARAMETER") childId: String, hours: Int, minutes: Int, startsInMinutes: Int? = null) {
        val total = hours * 60 + minutes
        if (total <= 0 && (startsInMinutes ?: 0) <= 0) { showFeedback("Choose how long to lock the phone for."); return }
        val label = if (total > 0) " for ${repository.formatDuration(total)}" else ""
        act("${_selectedChildName.value}'s phone is locked$label.") { f, c, n ->
            repository.lock(f, c, n, "Locked by your parent$label.", total.takeIf { it > 0 }, startsInMinutes)
        }
    }

    /**
     * Unlocks now. If bedtime or the daily limit is what is locking the phone, those are paused for an hour
     * so the unlock actually takes effect; after that the normal rules apply again.
     */
    fun remoteUnlock(@Suppress("UNUSED_PARAMETER") childId: String, pauseMinutes: Int? = null) {
        val state = currentDevice.value.lockState
        val pause = pauseMinutes ?: if (state == DeviceLockState.BEDTIME_LOCKED || state == DeviceLockState.LIMIT_REACHED) 60 else 0
        val note = if (pause > 0) " Bedtime and the daily limit are paused for ${repository.formatDuration(pause)}." else ""
        act("${_selectedChildName.value}'s phone is unlocked.$note") { f, c, n -> repository.unlock(f, c, n, pause) }
    }

    fun remoteSoundAlarm(@Suppress("UNUSED_PARAMETER") childId: String) =
        act("Playing an alarm on ${_selectedChildName.value}'s phone for 30 seconds.") { f, c, n -> repository.soundAlarm(f, c, n) }

    /** Asks the child's phone to open the Live screen-check consent; the child taps the system prompt. */
    fun startLiveCheck(@Suppress("UNUSED_PARAMETER") childId: String) =
        act("A Live check was sent to ${_selectedChildName.value}'s phone. They still need to allow the screen prompt.") { f, c, n ->
            repository.startLive(f, c, n)
        }

    fun addBonusTime(@Suppress("UNUSED_PARAMETER") childId: String, bonusMinutes: Int) {
        val childToday = currentDevice.value.usageDate ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        act("Added ${repository.formatDuration(bonusMinutes)} for today.") { f, c, n -> repository.addBonus(f, c, n, bonusMinutes, childToday) }
    }

    fun updateDailyLimit(@Suppress("UNUSED_PARAMETER") childId: String, minutes: Int, feedbackMessage: String? = null) =
        act(feedbackMessage ?: if (minutes <= 0) "Daily limit removed." else "Daily limit set to ${repository.formatDuration(minutes)}.") { f, c, n ->
            repository.setDailyLimit(f, c, n, minutes)
        }

    fun applyAgeRecommendation(childId: String, profile: AgeProfile) {
        val limit = ScreenTimeConfig.getDefaultLimitForAge(profile)
        updateDailyLimit(childId, limit, "Applied the recommended ${repository.formatDuration(limit)} for ${profile.title}.")
    }

    fun updateBedtimeSchedule(@Suppress("UNUSED_PARAMETER") childId: String, start: String, end: String, enabled: Boolean) =
        act(if (enabled) "Bedtime set to $start–$end." else "Bedtime turned off.") { f, c, n -> repository.setBedtime(f, c, n, start, end, enabled) }

    fun updateSchoolSchedule(@Suppress("UNUSED_PARAMETER") childId: String, start: String, end: String, enabled: Boolean) =
        act(if (enabled) "School hours set to $start–$end on weekdays." else "School hours turned off.") { f, c, n ->
            repository.setSchoolHours(f, c, n, start, end, enabled)
        }

    // Legacy overloads used by older call sites.
    fun updateDailyLimit(minutes: Int) = updateDailyLimit(_selectedChildId.value, minutes)

    fun toggleSchoolTime(enabled: Boolean) {
        val cur = currentDevice.value
        updateSchoolSchedule(_selectedChildId.value, cur.schoolStart, cur.schoolEnd, enabled)
    }

    fun applyAgeRecommendation(profile: AgeProfile) = applyAgeRecommendation(_selectedChildId.value, profile)

    private companion object { const val TAG = "KGuardScreenTime" }
}

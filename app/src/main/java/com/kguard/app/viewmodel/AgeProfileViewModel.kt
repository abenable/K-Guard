package com.kguard.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgeProfileViewModel(
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    /** The profile persisted on this device; reflects the SAVED value only (read by AppNavigation). */
    val selectedAgeProfile: StateFlow<AgeProfile> = preferencesRepository.ageProfileFlow
        .stateIn(
            scope = viewModelScope,
            // Eager so the persisted profile is loaded at VM creation instead of flashing the
            // DEFAULT placeholder on the first frame.
            started = SharingStarted.Eagerly,
            initialValue = AgeProfile.DEFAULT
        )

    /** True once the persisted profile has been read from DataStore. */
    private val _profileLoaded = MutableStateFlow(false)
    val profileLoaded: StateFlow<Boolean> = _profileLoaded.asStateFlow()

    /** Selection made in the picker but NOT yet saved; null means the saved profile is shown. */
    private val _pendingSelection = MutableStateFlow<AgeProfile?>(null)
    val pendingSelection: StateFlow<AgeProfile?> = _pendingSelection.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.ageProfileFlow.first()
            _profileLoaded.value = true
        }
    }

    /** Stage the selection; nothing is written until [confirmSelection]. */
    fun selectAgeProfile(profile: AgeProfile) {
        _pendingSelection.value = profile
    }

    /** Persist the staged selection (no-op when nothing was staged). */
    fun confirmSelection() {
        val pending = _pendingSelection.value ?: return
        _pendingSelection.value = null
        viewModelScope.launch {
            preferencesRepository.saveAgeProfile(pending)
        }
    }

    /** Discard the staged selection (e.g. when leaving the screen without saving). */
    fun clearSelection() {
        _pendingSelection.value = null
    }
}

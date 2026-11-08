package com.kguard.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kguard.app.data.model.ModerationUiState
import com.kguard.app.data.repository.ModerationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TextModerationViewModel(
    private val moderationRepository: ModerationRepository = ModerationRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<ModerationUiState>(ModerationUiState.Idle)
    val uiState: StateFlow<ModerationUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun submitTextForModeration(childId: String?) {
        val textToSubmit = _inputText.value.trim()
        if (textToSubmit.isEmpty()) {
            _uiState.value = ModerationUiState.Error("Please enter text to evaluate.")
            return
        }

        if (childId == null) {
            _uiState.value = ModerationUiState.Error("Add a child profile first so K-Guard knows which age rules to apply.")
            return
        }

        _uiState.value = ModerationUiState.Loading
        viewModelScope.launch {
            val result = moderationRepository.moderateText(textToSubmit, childId)
            result.fold(
                onSuccess = { response ->
                    _uiState.value = ModerationUiState.Success(
                        response = response,
                        originalContent = textToSubmit,
                        isImage = false
                    )
                },
                onFailure = { error ->
                    _uiState.value = ModerationUiState.Error(
                        error.localizedMessage ?: "Failed to moderate text content."
                    )
                }
            )
        }
    }

    fun resetState() {
        // "Clear & Evaluate New Text" must clear the input too, not just the result.
        _inputText.value = ""
        _uiState.value = ModerationUiState.Idle
    }
}

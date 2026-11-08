package com.kguard.app.viewmodel

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kguard.app.data.model.ModerationUiState
import com.kguard.app.data.repository.ModerationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageModerationViewModel(
    private val moderationRepository: ModerationRepository = ModerationRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<ModerationUiState>(ModerationUiState.Idle)
    val uiState: StateFlow<ModerationUiState> = _uiState.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    fun setSelectedImage(uri: Uri?) {
        // Every selection change starts a fresh evaluation: without this, a newly picked image
        // would still show the previous image's verdict.
        if (uri != _selectedImageUri.value) {
            _selectedImageUri.value = uri
            _uiState.value = ModerationUiState.Idle
        }
    }

    fun submitImageForModeration(context: Context, childId: String?) {
        val uri = _selectedImageUri.value
        if (uri == null) {
            _uiState.value = ModerationUiState.Error("Please select an image first.")
            return
        }
        if (childId == null) {
            _uiState.value = ModerationUiState.Error("Add a child profile first so K-Guard knows which age rules to apply.")
            return
        }

        _uiState.value = ModerationUiState.Loading
        viewModelScope.launch {
            val displayName = withContext(Dispatchers.IO) { resolveDisplayName(context, uri) }
            moderationRepository.moderateImage(context.applicationContext, uri, childId).fold(
                onSuccess = { response ->
                    _uiState.value = ModerationUiState.Success(
                        response = response,
                        originalContent = displayName,
                        isImage = true
                    )
                },
                onFailure = { error ->
                    _uiState.value = ModerationUiState.Error(
                        error.localizedMessage ?: "Failed to moderate image content."
                    )
                }
            )
        }
    }

    fun resetState() {
        _selectedImageUri.value = null
        _uiState.value = ModerationUiState.Idle
    }

    /** Friendly label for the result card; a raw content:// URI means nothing to parents. */
    private fun resolveDisplayName(context: Context, uri: Uri): String = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() } ?: "Selected image"
    } catch (e: Exception) {
        "Selected image"
    }
}

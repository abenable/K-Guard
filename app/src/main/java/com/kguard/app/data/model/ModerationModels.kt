package com.kguard.app.data.model

sealed interface ModerationUiState {
    object Idle : ModerationUiState
    object Loading : ModerationUiState
    data class Success(
        val response: ModerationResponse,
        val originalContent: String? = null,
        val isImage: Boolean = false
    ) : ModerationUiState
    data class Error(val message: String) : ModerationUiState
}

/** Result of a moderation check, as returned by the `moderateContent` Cloud Function. */
data class ModerationResponse(
    val category: String = "general",
    val riskScore: Float = 0.0f,
    val ageAppropriate: Boolean = true,
    val decision: String = "REVIEW",
    val reason: String = "K-Guard could not assess this content.",
    val context: String = "unclear",
    /** Whether this child profile may tap to reveal a blurred item (set by the server). */
    val canReveal: Boolean = false,
    val eventId: String? = null
) {
    val parsedDecision: ModerationDecision
        get() = ModerationDecision.fromString(decision)
}

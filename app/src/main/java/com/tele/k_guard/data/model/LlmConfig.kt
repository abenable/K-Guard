package com.tele.k_guard.data.model

import com.tele.k_guard.BuildConfig

/** Where K-Guard Live's screen checks are sent. */
enum class LlmProviderType(val displayName: String) {
    /** Google Gemini over its REST API, using the development keys in [GeminiKeyRotationManager]. */
    GEMINI("Google Gemini"),

    /** A model on our own server (for example behind an ngrok tunnel) speaking the OpenAI chat API. */
    SELF_HOSTED("Self-hosted model")
}

/**
 * AI model settings for K-Guard Live. To use the model on the physical server, put its ngrok URL in `local.properties`
 * (git-ignored) and rebuild:
 *
 * ```
 * kguard.ai.url=https://xxxx.ngrok-free.app          # base URL, or the full .../v1/chat/completions URL
 * kguard.ai.model=llama3.1:8b                          # model name the server expects
 * kguard.ai.key=                                       # optional bearer token
 * ```
 *
 * The server must accept OpenAI-style `POST /v1/chat/completions` (Ollama, vLLM, llama.cpp, LM Studio and
 * text-generation-webui all do). While no URL is set, Gemini is used. If the self-hosted model cannot be
 * reached, Live falls back to Gemini so the check still runs.
 */
object AiConfig {
    val selfHostedUrl: String = BuildConfig.KGUARD_AI_URL.trim()
    val selfHostedModel: String = BuildConfig.KGUARD_AI_MODEL.trim().ifBlank { "default" }
    val selfHostedKey: String = BuildConfig.KGUARD_AI_KEY.trim()

    /** Tried in order; a model Google has retired (404) moves on to the next one. */
    val geminiModels = listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-flash-latest")

    /** A cloud model is set up for K-Guard Live's extra categories; without one, Live runs on the phone only. */
    val cloudConfigured: Boolean
        get() = selfHostedUrl.isNotBlank() || GeminiKeyRotationManager.keyCount > 0

    val provider: LlmProviderType
        get() = if (selfHostedUrl.isNotBlank()) LlmProviderType.SELF_HOSTED else LlmProviderType.GEMINI

    const val MAX_HISTORY_TURNS = 16
    const val MAX_REPLY_TOKENS = 1024
    const val TIMEOUT_MS = 25_000
}

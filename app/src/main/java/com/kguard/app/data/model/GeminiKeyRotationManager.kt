package com.kguard.app.data.model

import com.kguard.app.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gemini API keys for K-Guard Live, read from `kguard.gemini.keys` in local.properties (git-ignored),
 * comma-separated. No key is ever committed; a build without keys simply has no Gemini fallback.
 * Keys set here are compiled into that APK.
 */
object GeminiKeyRotationManager {
    private val keys: List<String> = BuildConfig.KGUARD_GEMINI_KEYS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val index = AtomicInteger(0)

    val keyCount: Int get() = keys.size

    fun getCurrentKey(): String = keys.getOrElse(index.get() % keys.size.coerceAtLeast(1)) { "" }

    fun rotateKey() { if (keys.isNotEmpty()) index.updateAndGet { (it + 1) % keys.size } }
}

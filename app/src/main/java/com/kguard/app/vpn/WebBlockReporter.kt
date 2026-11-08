package com.kguard.app.vpn

import com.google.firebase.functions.FirebaseFunctions
import com.kguard.app.data.model.SafetyCategory
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports blocked lookups to the `reportWebBlock` Cloud Function so parents get an alert.
 * Throttled so a page that fires many lookups produces one alert, not hundreds.
 */
class WebBlockReporter(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    private val lastSent = ConcurrentHashMap<String, Long>()
    private val hourly = ArrayDeque<Long>()

    /** Returns true if a report was attempted. Failures are ignored: blocking never depends on reporting. */
    suspend fun report(childId: String, category: SafetyCategory?, domain: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "$childId|$domain"
        synchronized(hourly) {
            while (hourly.isNotEmpty() && now - hourly.first() > HOUR_MS) hourly.removeFirst()
            if (hourly.size >= MAX_PER_HOUR) return false
            hourly.addLast(now)
        }
        // Atomically claim the cooldown slot: of two concurrent blocks for the same domain exactly
        // one gets `now` back, so only that one reports.
        val claimed = lastSent.compute(key) { _, prev -> if (prev != null && now - prev < DOMAIN_COOLDOWN_MS) prev else now }
        if (claimed != now) return false
        pruneLastSent(now)
        return runCatching {
            functions.getHttpsCallable("reportWebBlock").call(
                mapOf("childId" to childId, "category" to (category?.id ?: "custom"), "domain" to domain)
            ).await()
        }.isSuccess
    }

    /** Keeps [lastSent] bounded: past a point, entries older than an hour can no longer block anyone. */
    private fun pruneLastSent(now: Long) {
        if (lastSent.size <= MAX_TRACKED) return
        lastSent.values.removeIf { now - it > HOUR_MS }
    }

    private companion object {
        const val DOMAIN_COOLDOWN_MS = 10 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val MAX_PER_HOUR = 20
        const val MAX_TRACKED = 256
    }
}

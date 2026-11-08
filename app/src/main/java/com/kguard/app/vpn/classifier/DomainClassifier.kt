package com.kguard.app.vpn.classifier

import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.SafetyCategory

/**
 * Judges a domain that is on no blocklist, entirely on-device.
 *
 * The result maps onto the existing [SafetyCategory] vocabulary so the filter's age rules
 * and parent category rules keep applying unchanged; the classifier only ever *names* a
 * category, it never decides the final action.
 */
interface DomainClassifier {
    /**
     * @return a verdict when the classifier is confident enough to name a category,
     *   or `null` when it is unsure — the domain then stays allowed, exactly as today.
     */
    fun classify(domain: String, age: AgeProfile): ClassifierVerdict?
}

/** One classifier judgement. Mirrors the server-side scoring shape in `functions/src/policy.ts`. */
data class ClassifierVerdict(
    val category: SafetyCategory,
    /** 0.0..1.0 — how sure the classifier is that it named the right category. */
    val confidence: Double,
    /** 0.0..1.0 — how harmful the content looks for the given age profile. */
    val riskScore: Double,
    /** Short plain-language reason, shown to parents. */
    val label: String
)

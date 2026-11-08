package com.kguard.app.vpn.classifier

import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.SafetyCategory

/**
 * The v0 domain classifier: a hand-tuned pattern scorer, pure Kotlin, no ML dependency.
 *
 * It looks at the whole domain string (registrable domain plus subdomains), tokenises it, and
 * accumulates per-category keyword weights. It returns a verdict whenever it is confident enough
 * to name a category, carrying a graded risk score; [DomainFilter] then applies the per-age
 * thresholds (mirroring `functions/src/policy.ts`) and the parent's category rules.
 *
 * A trained ONNX model implements [DomainClassifier] behind the same interface later; this
 * class is the always-available fallback and the behavioural contract for that swap.
 */
class HeuristicDomainClassifier : DomainClassifier {

    override fun classify(domain: String, age: AgeProfile): ClassifierVerdict? {
        val d = domain.trim().trimEnd('.').lowercase()
        if (d.isEmpty()) return null

        val tokens = tokenize(d)
        var best: Scored? = null
        for ((category, weights) in KEYWORDS) {
            val matched = matchedTokens(tokens, weights)
            if (matched.isEmpty()) continue
            // Stronger signal than one weak keyword; damp towards the cap rather than exceed it.
            var score = matched.values.sum() * (1.0 + (matched.size - 1) * 0.25)
            if (best == null || score > best.score) best = Scored(category, score, matched.size)
        }

        if (best == null) {
            // No harmful signal — but punycode/lookalike tricks alone justify a scam flag.
            if (isSuspiciousMechanics(d)) return verdict(SafetyCategory.SCAM, SCAM_PUNY_WEIGHT, 1, "looks like a disguised or lookalike domain")
            return null
        }
        // Benign vocabulary (school, academy, wiki…) pulls the risk down: a domain like
        // "sex-education-course.school.edu" should not read as adult content.
        val benign = BENIGN_TOKENS.count { it in tokens }
        best = best.copy(score = (best.score - benign * BENIGN_DAMPING).coerceAtLeast(0.0))

        if (isSuspiciousMechanics(d)) best = best.copy(score = best.score + SCAM_PUNY_WEIGHT / 4)

        val confidence = ((best.hits - 1) * 0.3 + best.score).coerceAtMost(0.99)
        if (confidence < CONFIDENCE_FLOOR) return null

        // The verdict carries the graded risk; the age threshold and the parent's category
        // rules are applied by DomainFilter, which keeps a parent's Allow authoritative.
        return verdict(best.category, best.score, best.hits, labelFor(best.category))
    }

    private fun verdict(category: SafetyCategory, score: Double, hits: Int, label: String) =
        ClassifierVerdict(category, ((hits - 1) * 0.3 + score).coerceAtMost(0.99), (score / MAX_SCORE).coerceIn(0.0, 1.0), label)

    private fun labelFor(category: SafetyCategory) = when (category) {
        SafetyCategory.SEXUAL -> "adult content"
        SafetyCategory.DRUGS -> "drugs"
        SafetyCategory.HATE -> "hate speech"
        SafetyCategory.VIOLENCE -> "violence"
        SafetyCategory.SELF_HARM -> "self-harm"
        SafetyCategory.SCAM -> "a likely scam"
        else -> "harmful content"
    }

    private data class Scored(val category: SafetyCategory, val score: Double, val hits: Int)

    companion object {
        /** Below this the classifier declines to judge — an unsure verdict must never block. */
        const val CONFIDENCE_FLOOR = 0.80
        /** Score at which a verdict's risk saturates; matches the server's score scale. */
        private const val MAX_SCORE = 1.6
        /** How much one benign token pulls the risk score down. */
        private const val BENIGN_DAMPING = 0.45
        /** Extra risk for punycode/lookalike domains when no keyword fired. */
        private const val SCAM_PUNY_WEIGHT = 0.9

        /** Block thresholds per age profile, mirroring `functions/src/policy.ts` DEFAULTS. */
        fun threshold(age: AgeProfile): Double = when (age) {
            AgeProfile.KIDS_3_7 -> 0.50
            AgeProfile.PRETEENS_8_12 -> 0.55
            AgeProfile.TEENS_13_17 -> 0.70
            AgeProfile.ADULTS_18_PLUS -> 0.92
        }

        /**
         * Splits into words on separators and digits. The full domain string is deliberately
         * *not* a token: it would match the same keywords as its own subdomains and double-count
         * them ("pornhub.com" scoring "porn" on both "pornhub" and "pornhub.com").
         */
        internal fun tokenize(domain: String): Set<String> =
            domain.split('.', '-', '_', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .filterTo(HashSet()) { it.isNotEmpty() }

        /**
         * A keyword matches a token when it equals it, prefixes it ("porn" → "pornhub"), or —
         * for keywords long enough to be unambiguous — sits anywhere inside it ("kill" →
         * "killing"). Short keywords stay prefix-only so "sex" cannot hide inside "essex",
         * "sussex" or "middlesex".
         */
        internal fun matchesToken(token: String, keyword: String): Boolean =
            token == keyword || token.startsWith(keyword) || (keyword.length >= 4 && keyword in token)

        /**
         * Maps each token to the weight of the keyword matched on it. Distinct tokens each
         * count ("porn-xxx.net" = 2 hits); overlapping keywords on one token keep only the
         * longest ("pornhub" beats "porn" on the "pornhub" token).
         */
        internal fun matchedTokens(tokens: Set<String>, weights: List<Pair<String, Double>>): Map<String, Double> {
            val out = HashMap<String, Double>()
            for ((keyword, weight) in weights)
                for (token in tokens)
                    if (matchesToken(token, keyword)) {
                        val existing = out[token]
                        if (existing == null || weight >= existing) out[token] = weight
                    }
            return out
        }

        /** Structural tricks: punycode homographs and typical lookalike patterns. */
        internal fun isSuspiciousMechanics(domain: String): Boolean =
            domain.split('.').any { it.startsWith("xn--") } || "а" in domain || "о" in domain || "е" in domain

        /**
         * Keyword weights per category. Calibration:
         * risk = weight / 1.6, so a single hit blocks an age profile when
         * `weight / 1.6 >= threshold(age)` — i.e. ≥ 0.80 for kids/preteens, ≥ 1.12 for teens,
         * ≥ 1.47 for adults. Deliberately small lists — precision beats recall, because a wrong
         * block is worse than a missed one. Weak signals (< 0.8 confidence) can never block.
         */
        private val KEYWORDS: Map<SafetyCategory, List<Pair<String, Double>>> = mapOf(
            SafetyCategory.SEXUAL to listOf(
                "porn" to 1.3, "pornhub" to 1.3, "xvideos" to 1.3, "xhamster" to 1.3,
                "redtube" to 1.3, "chaturbate" to 1.3, "xxx" to 1.3, "onlyfans" to 1.2,
                "hentai" to 1.2, "milf" to 1.2, "camgirl" to 1.2, "nsfw" to 1.0,
                "nude" to 1.0, "tits" to 1.1, "sex" to 1.15, "escort" to 1.0, "hookup" to 0.9,
                "horny" to 0.9, "fap" to 0.9, "erotic" to 0.9, "adult" to 0.6, "dating" to 0.4
            ),
            SafetyCategory.DRUGS to listOf(
                "cannabis" to 1.2, "cocaine" to 1.3, "meth" to 1.3, "heroin" to 1.3,
                "weed" to 1.2, "ketamine" to 1.2, "dispensary" to 1.2, "shrooms" to 1.0,
                "kratom" to 1.0, "lsd" to 0.6, "vape" to 0.5
            ),
            SafetyCategory.HATE to listOf(
                "nazi" to 1.3, "supremacist" to 1.3, "kkk" to 1.3, "holohoax" to 1.3,
                "groyper" to 1.2, "jihad" to 0.9, "terror" to 0.6
            ),
            SafetyCategory.VIOLENCE to listOf(
                "gore" to 1.3, "beheading" to 1.3, "snuff" to 1.3, "torture" to 1.0,
                "bloodfest" to 1.2, "kill" to 0.4, "shooting" to 0.4
            ),
            SafetyCategory.SELF_HARM to listOf(
                "suicide" to 1.3, "selfharm" to 1.3, "proana" to 1.3,
                "thinspo" to 1.3, "cutting" to 0.5
            ),
            SafetyCategory.SCAM to listOf(
                "phishing" to 1.5, "freerobux" to 1.5, "freemoney" to 1.5, "getrich" to 1.5,
                "winprize" to 1.5, "freecrypto" to 1.4, "keygen" to 1.2, "nulled" to 1.2,
                "crack" to 0.8, "casino" to 0.6, "betting" to 0.6, "jackpot" to 0.6,
                "giveaway" to 0.5, "hack" to 0.5, "airdrop" to 0.4
            ),
            SafetyCategory.CYBERBULLYING to listOf(
                "haterz" to 0.8, "roast" to 0.4, "diss" to 0.4
            )
        )

        /** Recognised-benign vocabulary; enough of these and weak keyword hits stop mattering. */
        private val BENIGN_TOKENS = setOf(
            "edu", "ac", "school", "schools", "k12", "university", "uni", "college", "academy",
            "learn", "learning", "lesson", "lessons", "course", "courses", "class", "classes",
            "study", "studies", "teach", "teacher", "teachers", "education", "educational",
            "wiki", "wikipedia", "encyclopedia", "library", "museum", "kids", "family", "child",
            "children", "health", "clinic", "nhs", "gov", "homework", "help", "support",
            "safety", "parent", "parents", "parenting", "science", "math", "reading", "history"
        )
    }
}

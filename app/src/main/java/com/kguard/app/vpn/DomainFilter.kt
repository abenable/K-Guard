package com.kguard.app.vpn

import android.content.Context
import android.util.Log
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.CategoryRule
import com.kguard.app.data.model.SafetyCategory
import com.kguard.app.vpn.classifier.CachedDomainClassifier
import com.kguard.app.vpn.classifier.DomainClassifier
import com.kguard.app.vpn.classifier.HeuristicDomainClassifier
import com.kguard.app.vpn.classifier.VerdictCache

/** What the filter needs to know about the protected child. */
data class VpnPolicy(
    val ageProfile: AgeProfile,
    val rules: Map<String, String> = emptyMap(),
    val customBlock: Set<String> = emptySet(),
    val customAllow: Set<String> = emptySet(),
    /** Let recognised educational sites through even if a category list would block them. */
    val educationalAllow: Boolean = true,
    /** Judge unlisted domains with the on-device AI filter (see docs/spec-web-classifier.md). */
    val aiFilterEnabled: Boolean = true
)

enum class Reason {
    ALLOWED, ALLOWED_LISTED, ALLOWED_PARENT, ALLOWED_EDUCATIONAL, ALLOWED_BY_RULE, ALLOWED_AI,
    BLOCKED_CUSTOM, BLOCKED_AGE, BLOCKED_RULE, BLOCKED_LOCKED, BLOCKED_AI, BLOCKED_DOH
}

data class Verdict(
    val blocked: Boolean,
    /** Category that triggered the decision; null for custom lists or an unlisted site. */
    val category: SafetyCategory? = null,
    val custom: Boolean = false,
    val reason: Reason = Reason.ALLOWED
)

/** Where the effective action for a category comes from. */
enum class Source { AGE, RULE, LOCKED }

data class Effective(val blocked: Boolean, val source: Source)

/** Decides whether a DNS name should be blocked. Pure logic, no Android dependencies beyond list loading. */
class DomainFilter(
    private val lists: Map<SafetyCategory, Set<String>>,
    private val educational: Set<String> = emptySet(),
    /** Hostnames of encrypted-DNS resolvers, always blocked so filtering cannot be bypassed. */
    private val doh: Set<String> = emptySet(),
    /** On-device judge for domains on no list; null disables the AI step entirely. */
    private val classifier: DomainClassifier? = null,
    /** True when a blocklist asset was missing or unreadable, so a category may not be filtered at all. */
    val hadLoadErrors: Boolean = false
) {

    fun listSize(category: SafetyCategory): Int = lists[category]?.size ?: 0

    fun evaluate(rawDomain: String, policy: VpnPolicy): Verdict {
        val domain = normalise(rawDomain)
        if (domain.isEmpty()) return Verdict(false)

        // Android's Private-DNS probe: NXDOMAIN makes the system give up on encrypted DNS,
        // so lookups re-enter this filter instead of bypassing it.
        if (domain == "use-application-dns.net") return Verdict(true, reason = Reason.BLOCKED_DOH)

        // Encrypted-DNS resolvers are protective infrastructure: they are not content, so no
        // allowlist may re-enable them.
        if (matches(domain, doh)) return Verdict(true, reason = Reason.BLOCKED_DOH)

        if (matches(domain, policy.customAllow)) return Verdict(false, reason = Reason.ALLOWED_PARENT)
        if (matches(domain, policy.customBlock)) return Verdict(true, custom = true, reason = Reason.BLOCKED_CUSTOM)
        if (policy.educationalAllow && matches(domain, educational)) {
            return Verdict(false, reason = Reason.ALLOWED_EDUCATIONAL)
        }

        // A domain can be listed in several categories; if ANY of them blocks for this profile, block.
        val matched = lists.entries.filter { matches(domain, it.value) }
            .map { it.key to effective(it.key, policy) }
        val blocking = matched.filter { it.second.blocked }
        // Explain with the strongest authority: locked, then parent rule, then age default.
        val chosen = (if (blocking.isNotEmpty()) blocking else matched).minByOrNull { rank(it.second) }
            ?: return classifyFallback(domain, policy)

        val category = chosen.first
        val e = chosen.second
        val reason = when {
            e.blocked && e.source == Source.RULE -> Reason.BLOCKED_RULE
            e.blocked && e.source == Source.LOCKED -> Reason.BLOCKED_LOCKED
            e.blocked -> Reason.BLOCKED_AGE
            e.source == Source.RULE -> Reason.ALLOWED_BY_RULE
            else -> Reason.ALLOWED_LISTED // on a list, but the age profile allows it
        }
        return Verdict(e.blocked, category, reason = reason)
    }

    /**
     * Nothing listed the domain, so the on-device classifier judges it. The verdict's graded
     * risk is checked against the age threshold (mirroring `functions/src/policy.ts`), and only
     * then against the same category rules a listed domain would face — so a parent's Allow
     * stays authoritative even for a high-risk verdict. An unsure classifier leaves the site
     * allowed, exactly as before this feature existed.
     */
    private fun classifyFallback(domain: String, policy: VpnPolicy): Verdict {
        val verdict = classifier?.takeIf { policy.aiFilterEnabled }
            ?.classify(domain, policy.ageProfile) ?: return Verdict(false)
        if (verdict.riskScore < HeuristicDomainClassifier.threshold(policy.ageProfile)) {
            return Verdict(false, verdict.category, reason = Reason.ALLOWED_AI)
        }
        val eff = effective(verdict.category, policy)
        return if (eff.blocked) Verdict(true, verdict.category, reason = Reason.BLOCKED_AI)
        else Verdict(false, verdict.category, reason = Reason.ALLOWED_AI)
    }

    companion object {
        /** Lower is a stronger authority: LOCKED beats a parent rule, which beats the age default. */
        private fun rank(e: Effective): Int = when (e.source) {
            Source.LOCKED -> 0; Source.RULE -> 1; Source.AGE -> 2
        }

        fun normalise(domain: String) = domain.trim().trimEnd('.').lowercase()

        /** The action DNS filtering takes for [category]: parent rule if set, otherwise the age default. */
        fun effective(category: SafetyCategory, policy: VpnPolicy): Effective =
            when (CategoryRule.fromId(policy.rules[category.id])) {
                CategoryRule.BLOCK -> Effective(true, Source.RULE)
                // A parent may allow a category, but never sexual content for under-18s.
                CategoryRule.ALLOW ->
                    if (category == SafetyCategory.SEXUAL && policy.ageProfile != AgeProfile.ADULTS_18_PLUS)
                        Effective(true, Source.LOCKED)
                    else Effective(false, Source.RULE)
                // DNS can only block or allow, so Warn and Blur behave as allow here.
                CategoryRule.WARN, CategoryRule.BLUR -> Effective(false, Source.RULE)
                null -> Effective(defaultBlocks(policy.ageProfile, category), Source.AGE)
            }

        /** Plain-language explanation of a decision, shown to parents and children. */
        fun explain(v: Verdict, policy: VpnPolicy): String {
            val what = v.category?.label?.lowercase() ?: "this category"
            return when (v.reason) {
                Reason.BLOCKED_AGE -> "Blocked: $what isn't suitable for ${policy.ageProfile.ageRange.replaceFirstChar { it.lowercase() }}."
                Reason.BLOCKED_RULE -> "Blocked: a parent chose to block $what."
                Reason.BLOCKED_LOCKED -> "Blocked: sexual content stays blocked under 18, even when a parent allows it."
                Reason.BLOCKED_CUSTOM -> "Blocked: a parent added this site to the block list."
                Reason.ALLOWED_EDUCATIONAL -> "Allowed: recognised educational site."
                Reason.ALLOWED_PARENT -> "Allowed: a parent always allows this site."
                Reason.ALLOWED_BY_RULE -> "Allowed: a parent allows $what for this profile."
                Reason.ALLOWED_LISTED -> "Found in the $what list — allowed by the ${policy.ageProfile.title} profile. The lists are limited, so this is not a guarantee of safety."
                Reason.ALLOWED -> "Allowed: nothing found in K-Guard's site lists. The lists are limited, so this is not a guarantee of safety."
                Reason.BLOCKED_AI -> "Blocked: the K-Guard AI filter judged this site to be $what — it was on no list, but it doesn't look suitable for ${policy.ageProfile.ageRange.replaceFirstChar { it.lowercase() }}."
                Reason.ALLOWED_AI -> "The AI filter checked this site and found no clear problems for the ${policy.ageProfile.title} profile. This is not a guarantee of safety."
                Reason.BLOCKED_DOH -> "Blocked: encrypted-DNS resolvers are not allowed while protection is on, so K-Guard can keep filtering websites."
            }
        }

        /** True if [domain] equals a listed entry or is a subdomain of one. */
        fun matches(domain: String, set: Set<String>): Boolean {
            if (set.isEmpty()) return false
            var d = domain
            while (true) {
                if (d in set) return true
                val dot = d.indexOf('.')
                if (dot < 0) return false
                d = d.substring(dot + 1)
            }
        }

        fun defaultBlocks(age: AgeProfile, category: SafetyCategory): Boolean = when (age) {
            AgeProfile.KIDS_3_7, AgeProfile.PRETEENS_8_12 -> true
            AgeProfile.TEENS_13_17 -> category in setOf(
                SafetyCategory.SEXUAL, SafetyCategory.DRUGS, SafetyCategory.HATE,
                SafetyCategory.VIOLENCE, SafetyCategory.SCAM
            )
            AgeProfile.ADULTS_18_PLUS -> category == SafetyCategory.SCAM
        }

        /**
         * Parses one-domain-per-line text, ignoring blanks and `#` comments. Wildcard (`*.example.com`)
         * and leading-dot (`.example.com`) entries are expanded to `example.com`, which [matches] treats
         * as the domain and every subdomain of it.
         */
        fun parseList(text: String): Set<String> =
            text.lineSequence().map { it.substringBefore('#').trim().lowercase() }
                .map { it.removePrefix("*.").removePrefix(".") }
                .filter { it.isNotEmpty() }.toSet()

        private const val TAG = "KGuardFilter"

        /**
         * Reads one asset; a missing or corrupt file is a real failure (the category would silently
         * allow everything), so it is logged loudly and reported through [DomainFilter.hadLoadErrors].
         */
        private fun readAsset(context: Context, name: String, errors: MutableList<String>): Set<String> =
            try {
                context.assets.open("blocklists/$name.txt").bufferedReader().use { parseList(it.readText()) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load blocklist asset 'blocklists/$name.txt'", e)
                errors += name
                emptySet()
            }

        /** Loads `assets/blocklists/<category id>.txt` per category, plus `educational.txt`,
         *  `doh.txt`, and wires the on-device classifier (cached across instances). */
        fun load(context: Context): DomainFilter {
            val map = LinkedHashMap<SafetyCategory, Set<String>>()
            val errors = ArrayList<String>()
            for (c in SafetyCategory.entries) {
                val set = readAsset(context, c.id, errors)
                if (set.isNotEmpty()) map[c] = set
            }
            val classifier = CachedDomainClassifier(HeuristicDomainClassifier(), VerdictCache.from(context))
            return DomainFilter(
                map,
                readAsset(context, "educational", errors),
                doh = readAsset(context, "doh", errors),
                classifier = classifier,
                hadLoadErrors = errors.isNotEmpty()
            )
        }
    }
}

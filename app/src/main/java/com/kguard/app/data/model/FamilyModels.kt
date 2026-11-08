package com.kguard.app.data.model

import com.google.firebase.Timestamp

/** Content categories. `id` matches the Cloud Functions policy engine. */
enum class SafetyCategory(val id: String, val label: String, val hint: String) {
    CYBERBULLYING("cyberbullying", "Bullying & harassment", "Insults, exclusion, targeted meanness"),
    THREAT("threat", "Threats", "Threats of harm to a person"),
    VIOLENCE("violence", "Violence", "Graphic or violent content"),
    SEXUAL("sexual", "Sexual content", "Explicit or suggestive material"),
    SELF_HARM("self_harm", "Self-harm", "Content about hurting oneself"),
    HATE("hate", "Hate speech", "Hateful content about groups of people"),
    DRUGS("drugs", "Drugs & alcohol", "Drug use or promotion"),
    PROFANITY("profanity", "Strong language", "Swearing and crude language"),
    SCAM("scam", "Scams", "Prizes, phishing, requests for passwords"),
    PERSONAL_INFO("personal_info", "Personal information", "Addresses, phone numbers, IDs");

    /** Categories that parents may only tighten, never relax (enforced again on the server). */
    val lockedAgainstRelaxing: Boolean
        get() = this == SEXUAL || this == THREAT || this == SELF_HARM
}

enum class CategoryRule(val id: String, val label: String) {
    BLOCK("block", "Block"),
    BLUR("blur", "Blur"),
    WARN("warn", "Warn"),
    ALLOW("allow", "Allow");

    companion object {
        fun fromId(id: String?): CategoryRule? = entries.find { it.id == id }
    }
}

/** A child profile. Firestore-mapped, so every property needs a default. */
data class ChildProfile(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val ageProfile: String = AgeProfile.PRETEENS_8_12.id,
    val categoryRules: Map<String, String> = emptyMap(),
    val allowReveal: Boolean = false,
    val linkedUid: String? = null,
    val createdAt: Timestamp? = null,
    val photoUrl: String = ""
) {
    val age: AgeProfile get() = AgeProfile.fromId(ageProfile)
    fun ruleFor(category: SafetyCategory): CategoryRule? = CategoryRule.fromId(categoryRules[category.id])
}

data class QuietHours(
    val enabled: Boolean = false,
    val start: String = "21:00",
    val end: String = "07:00",
    /** Minutes east of UTC for the parent's phone, so the server evaluates quiet hours in local time. */
    val utcOffsetMinutes: Int = 0
)

/** Per-family privacy and notification settings. */
data class FamilyPrivacy(
    val notifyOn: String = NOTIFY_WARN_AND_ABOVE,
    val includeSnippet: Boolean = false,
    val retainDays: Int = 30,
    val quietHours: QuietHours = QuietHours()
) {
    companion object {
        const val NOTIFY_ALL = "all"
        const val NOTIFY_WARN_AND_ABOVE = "warn_and_above"
        const val NOTIFY_BLOCK_ONLY = "block_only"
    }
}

data class SafetyEvent(
    val id: String = "",
    val childId: String = "",
    val childName: String = "",
    val ageProfile: String = "",
    val contentType: String = "text",
    val category: String = "none",
    val riskScore: Double = 0.0,
    val decision: String = "REVIEW",
    val context: String = "unclear",
    val explanation: String = "",
    val source: String = "",
    val snippet: String? = null,
    val status: String = "open",
    val parentAction: String? = null,
    val parentNote: String? = null,
    val createdAt: Timestamp? = null
) {
    val parsedDecision: ModerationDecision get() = ModerationDecision.fromString(decision)
    val isOpen: Boolean get() = status == "open"
}

enum class ParentAction(val id: String, val label: String) {
    ALLOW("allow", "Allow"),
    KEEP_BLOCKED("keep_blocked", "Keep blocked"),
    DISMISS("dismiss", "Dismiss")
}

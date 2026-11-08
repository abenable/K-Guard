package com.kguard.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AgeProfile(
    val id: String,
    val title: String,
    val ageRange: String,
    val description: String
) {
    KIDS_3_7(
        id = "KIDS_3_7",
        title = "Early Childhood",
        ageRange = "Ages 3 – 7",
        description = "Maximum restriction. Strictly blocks violent, explicit, and sensitive topics with supportive guidance."
    ),
    PRETEENS_8_12(
        id = "PRETEENS_8_12",
        title = "Pre-Teens",
        ageRange = "Ages 8 – 12",
        description = "Balanced protection. Applies warnings and previews for mild content while blocking harmful materials."
    ),
    TEENS_13_17(
        id = "TEENS_13_17",
        title = "Teens",
        ageRange = "Ages 13 – 17",
        description = "Age-appropriate safety. Focuses on severe safety risks and explicit content warnings."
    ),
    ADULTS_18_PLUS(
        id = "ADULTS_18_PLUS",
        title = "Adults",
        ageRange = "Ages 18+",
        description = "Standard moderation. Flags illegal or hazardous content."
    );

    companion object {
        val DEFAULT = PRETEENS_8_12

        fun fromId(id: String?): AgeProfile {
            return entries.find { it.id == id } ?: DEFAULT
        }
    }
}

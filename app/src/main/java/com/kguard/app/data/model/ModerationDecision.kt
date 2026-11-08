package com.kguard.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ModerationDecision {
    ALLOW,
    WARN,
    BLUR,
    BLOCK,
    REVIEW;

    companion object {
        fun fromString(value: String?): ModerationDecision {
            return when (value?.uppercase()) {
                "ALLOW" -> ALLOW
                "WARN" -> WARN
                "BLUR" -> BLUR
                "BLOCK" -> BLOCK
                "REVIEW" -> REVIEW
                else -> REVIEW
            }
        }
    }
}

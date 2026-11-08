package com.kguard.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ScreenTimeConfig(
    val dailyLimitMinutes: Int = 120,
    val bedtimeStart: String = "21:00",
    val bedtimeEnd: String = "07:00",
    val schoolTimeEnabled: Boolean = true,
    val schoolTimeStart: String = "08:30",
    val schoolTimeEnd: String = "15:30",
    val usedTodayMinutes: Int = 45
) {
    companion object {
        fun getRecommendationForAge(profile: AgeProfile): String {
            return when (profile) {
                AgeProfile.KIDS_3_7 -> "Recommended daily limit: 1 hour (60 min). Strict bedtime at 20:00."
                AgeProfile.PRETEENS_8_12 -> "Recommended daily limit: 1.5 hours (90 min). Bedtime at 21:00."
                AgeProfile.TEENS_13_17 -> "Recommended daily limit: 2 to 2.5 hours. Bedtime at 22:00."
                AgeProfile.ADULTS_18_PLUS -> "No strict limit required. Healthy digital balance suggested."
            }
        }

        fun getDefaultLimitForAge(profile: AgeProfile): Int {
            return when (profile) {
                AgeProfile.KIDS_3_7 -> 60
                AgeProfile.PRETEENS_8_12 -> 90
                AgeProfile.TEENS_13_17 -> 150
                AgeProfile.ADULTS_18_PLUS -> 300
            }
        }
    }
}

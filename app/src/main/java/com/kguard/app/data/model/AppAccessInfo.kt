package com.kguard.app.data.model

import com.google.firebase.Timestamp

enum class AppApprovalStatus {
    APPROVED,
    PENDING_REVIEW,
    REJECTED;

    companion object {
        fun fromString(value: String?): AppApprovalStatus {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: PENDING_REVIEW
        }
    }
}

data class AppAccessInfo(
    val id: String = "",
    val appName: String = "",
    val packageName: String = "",
    val category: String = "General",
    val minAgeRating: Int = 3,
    val safetyScore: Int = 85,
    val approvalStatus: AppApprovalStatus = AppApprovalStatus.PENDING_REVIEW,
    /** Per-app daily limit on the child's phone; 0 means no per-app limit. */
    val dailyLimitMinutes: Int = 0,
    val safetyReason: String = "",
    val childId: String = "",
    val childName: String = "",
    val childLinkedUid: String? = null,
    val requestedAt: Timestamp? = null,
    val reviewedAt: Timestamp? = null,
    val reviewedBy: String? = null,
    /** False once the child's phone reports the app was uninstalled. */
    val installed: Boolean = true,
    val uninstalledAt: Timestamp? = null,
    /** "existing" (on the phone at setup), "device_install" (installed by the child) or "parent_added". */
    val source: String? = null
) {
    val isPending: Boolean
        get() = approvalStatus == AppApprovalStatus.PENDING_REVIEW

    val isApproved: Boolean
        get() = approvalStatus == AppApprovalStatus.APPROVED

    val isRejected: Boolean
        get() = approvalStatus == AppApprovalStatus.REJECTED
}

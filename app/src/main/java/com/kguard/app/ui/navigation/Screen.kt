package com.kguard.app.ui.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object AgeProfile : Screen("age_profile")
    object Home : Screen("home")
    object TextModeration : Screen("text_moderation")
    object ImageModeration : Screen("image_moderation")
    object ScreenTime : Screen("screen_time")
    object AppManagement : Screen("app_management")
    object ReviewQueue : Screen("review_queue")
    object SignIn : Screen("sign_in")
    object Children : Screen("children")
    object ChildEditor : Screen("child_editor?childId={childId}") {
        fun create(childId: String?) = if (childId == null) "child_editor" else "child_editor?childId=$childId"
    }
    object Privacy : Screen("privacy")
    object WebProtection : Screen("web_protection")
    object ChildPinSetup : Screen("child_pin_setup")
    object UninstallProtection : Screen("uninstall_protection")
    object ChildDashboard : Screen("child_dashboard")
    object AppDetail : Screen("app_detail?appId={appId}") {
        fun create(appId: String) = "app_detail?appId=$appId"
    }
    object Profile : Screen("profile")
}

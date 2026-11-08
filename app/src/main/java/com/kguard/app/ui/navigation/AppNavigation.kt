package com.kguard.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kguard.app.data.model.AppApprovalStatus
import com.kguard.app.data.repository.UserPreferencesRepository
import com.kguard.app.ui.screens.AgeProfileScreen
import com.kguard.app.ui.screens.AppDetailScreen
import com.kguard.app.ui.screens.AppManagementScreen
import com.kguard.app.ui.screens.ChildDashboardScreen
import com.kguard.app.ui.screens.ChildEditorScreen
import com.kguard.app.ui.screens.ChildPinSetupScreen
import com.kguard.app.ui.screens.ChildrenScreen
import com.kguard.app.ui.screens.HomeScreen
import com.kguard.app.ui.screens.ImageModerationScreen
import com.kguard.app.ui.screens.OnboardingScreen
import com.kguard.app.ui.screens.PrivacyScreen
import com.kguard.app.ui.screens.ProfileScreen
import com.kguard.app.ui.screens.ReviewQueueScreen
import com.kguard.app.ui.screens.ScreenTimeScreen
import com.kguard.app.ui.screens.SignInScreen
import com.kguard.app.ui.screens.TextModerationScreen
import com.kguard.app.ui.screens.UninstallProtectionScreen
import com.kguard.app.ui.screens.WebProtectionScreen
import com.kguard.app.viewmodel.AgeProfileViewModel
import com.kguard.app.viewmodel.AgeProfileViewModelFactory
import com.kguard.app.viewmodel.AppManagementViewModel
import com.kguard.app.viewmodel.ImageModerationViewModel
import com.kguard.app.viewmodel.ScreenTimeViewModel
import com.kguard.app.viewmodel.SessionViewModel
import com.kguard.app.viewmodel.TextModerationViewModel
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    targetRoute: String? = null,
    targetRouteId: Int = 0,
    onTargetRouteConsumed: () -> Unit = {}
) {
    val context = LocalContext.current.applicationContext
    val userPreferencesRepository = UserPreferencesRepository(context)
    val scope = rememberCoroutineScope()
    val ageProfileViewModel: AgeProfileViewModel = viewModel(
        factory = AgeProfileViewModelFactory(userPreferencesRepository)
    )
    val devicePreferredProfile by ageProfileViewModel.selectedAgeProfile.collectAsState()
    val deviceProfileLoaded by ageProfileViewModel.profileLoaded.collectAsState()

    val session: SessionViewModel = viewModel()
    val user by session.user.collectAsState()
    val signedIn by session.signedIn.collectAsState()
    val authChecked by session.authChecked.collectAsState()
    val selectedChild by session.selectedChild.collectAsState()
    val openAlerts by session.openAlertCount.collectAsState()
    val familyId by session.familyId.collectAsState()
    val children by session.children.collectAsState()

    val isChildDevice by userPreferencesRepository.isChildDeviceFlow.collectAsState(initial = false)
    val parentPinHash by userPreferencesRepository.parentPinHashFlow.collectAsState(initial = null)

    // The selected child's age group drives every check; before any child exists, fall back to the device setting.
    val activeAgeProfile = selectedChild?.age ?: devicePreferredProfile
    val activeChildId = selectedChild?.id
    // A child's own profile is authoritative the moment it loads; the device setting only counts once it loads.
    val ageProfileLoaded = selectedChild != null || deviceProfileLoaded

    val textModerationViewModel: TextModerationViewModel = viewModel()
    val imageModerationViewModel: ImageModerationViewModel = viewModel()
    val screenTimeViewModel: ScreenTimeViewModel = viewModel()
    val appManagementViewModel: AppManagementViewModel = viewModel()

    // Every child's apps: the App Management screen filters by child on screen, and the pending badge counts all.
    LaunchedEffect(familyId) {
        appManagementViewModel.setFamilyAndChild(familyId, null)
    }

    val managedApps by appManagementViewModel.managedApps.collectAsState()
    val pendingApprovalsCount = managedApps.count { it.approvalStatus == AppApprovalStatus.PENDING_REVIEW }

    // Signing out (or an expired session) returns to the sign-in screen from anywhere.
    LaunchedEffect(signedIn, authChecked) {
        if (authChecked && !signedIn) {
            val current = navController.currentDestination?.route
            if (current != null && current != Screen.SignIn.route && current != Screen.Onboarding.route) {
                navController.navigate(Screen.SignIn.route) { popUpTo(0) }
            }
        }
    }

    // A child's phone may only reach the child screens; anything else (parent dashboard, rules, approvals)
    // sends it back to its own dashboard. Child phones are the anonymous identities created at pairing.
    val isChildIdentity by session.isChildDevice.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(signedIn, isChildIdentity, currentRoute) {
        if (!signedIn || !isChildIdentity || currentRoute == null) return@LaunchedEffect
        val childRoutes = setOf(
            Screen.Onboarding.route, Screen.SignIn.route, Screen.ChildPinSetup.route,
            Screen.UninstallProtection.route, Screen.ChildDashboard.route, Screen.Profile.route
        )
        if (currentRoute !in childRoutes) {
            val target = if (parentPinHash.isNullOrBlank()) Screen.ChildPinSetup.route else Screen.ChildDashboard.route
            navController.navigate(target) { popUpTo(0) }
        }
    }

    // Handle deep navigation from FCM push notifications. targetRouteId lets repeat taps of the
    // same route re-fire this effect; the route is consumed after navigating.
    LaunchedEffect(targetRoute, targetRouteId, signedIn) {
        if (signedIn && targetRoute != null) {
            // Parent screens only; the child guard above keeps child phones on their own screens.
            if (!isChildIdentity) when (targetRoute) {
                "app_management" -> navController.navigate(Screen.AppManagement.route) { launchSingleTop = true }
                "review_queue" -> navController.navigate(Screen.ReviewQueue.route) { launchSingleTop = true }
                "screen_time" -> navController.navigate(Screen.ScreenTime.route) { launchSingleTop = true }
                "web_protection" -> navController.navigate(Screen.WebProtection.route) { launchSingleTop = true }
            }
            onTargetRouteConsumed()
        }
    }

    if (!authChecked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val startDestination = if (signedIn) {
        if (isChildDevice) {
            if (parentPinHash.isNullOrBlank()) Screen.ChildPinSetup.route else Screen.ChildDashboard.route
        } else {
            Screen.Home.route
        }
    } else {
        Screen.Onboarding.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onGetStarted = { navController.navigate(Screen.SignIn.route) }
            )
        }

        composable(Screen.SignIn.route) {
            SignInScreen(
                session = session,
                onParentSignedIn = {
                    navController.navigate(Screen.Home.route) { popUpTo(0) }
                },
                onChildPaired = {
                    navController.navigate(Screen.ChildPinSetup.route) { popUpTo(0) }
                }
            )
        }

        composable(Screen.ChildPinSetup.route) {
            ChildPinSetupScreen(
                onPinSaved = { pin ->
                    scope.launch {
                        userPreferencesRepository.saveParentPin(pin)
                        // On a freshly paired child device the children list may not have loaded
                        // yet, so prefer the childId captured during pairing.
                        userPreferencesRepository.saveChildDeviceMode(true, session.selectedChildId.value ?: activeChildId)
                        navController.navigate(Screen.UninstallProtection.route) { popUpTo(0) }
                    }
                }
            )
        }

        composable(Screen.UninstallProtection.route) {
            UninstallProtectionScreen(
                onProtected = {
                    navController.navigate(Screen.ChildDashboard.route) { popUpTo(0) }
                },
                onSkipped = {
                    navController.navigate(Screen.ChildDashboard.route) { popUpTo(0) }
                }
            )
        }

        composable(Screen.ChildDashboard.route) {
            ChildDashboardScreen(
                childProfile = selectedChild,
                familyId = familyId,
                onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                // Pairing is the code step on the sign-in screen; this device keeps its anonymous identity.
                onPairDevice = { navController.navigate(Screen.SignIn.route) }
            )
        }

        composable(Screen.Profile.route) {
            ProfileScreen(
                session = session,
                isChildDevice = isChildDevice,
                onSignOut = {
                    scope.launch {
                        userPreferencesRepository.saveChildDeviceMode(false)
                        navController.navigate(Screen.SignIn.route) { popUpTo(0) }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.AgeProfile.route) {
            AgeProfileScreen(
                viewModel = ageProfileViewModel,
                onSaveAndContinue = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                activeAgeProfile = activeAgeProfile,
                activeChildName = selectedChild?.name,
                openAlertCount = openAlerts,
                pendingApprovalCount = pendingApprovalsCount,
                onNavigateToTextModeration = { navController.navigate(Screen.TextModeration.route) },
                onNavigateToImageModeration = { navController.navigate(Screen.ImageModeration.route) },
                onNavigateToAgeProfile = { navController.navigate(Screen.AgeProfile.route) },
                onNavigateToScreenTime = { navController.navigate(Screen.ScreenTime.route) },
                onNavigateToAppManagement = { navController.navigate(Screen.AppManagement.route) },
                onNavigateToFlaggedContent = { navController.navigate(Screen.ReviewQueue.route) },
                onNavigateToPrivacy = { navController.navigate(Screen.Privacy.route) },
                onNavigateToWebProtection = { navController.navigate(Screen.WebProtection.route) },
                onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                onNavigateToChildren = { navController.navigate(Screen.Children.route) },
                children = children,
                selectedChild = selectedChild,
                familyId = familyId,
                onSelectChild = { session.selectChild(it) },
                onAddChild = { navController.navigate(Screen.ChildEditor.create(null)) }
            )
        }

        composable(Screen.Children.route) {
            ChildrenScreen(
                session = session,
                onEditChild = { navController.navigate(Screen.ChildEditor.create(it)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ChildEditor.route,
            arguments = listOf(navArgument("childId") { type = NavType.StringType; nullable = true; defaultValue = null })
        ) { entry ->
            ChildEditorScreen(
                session = session,
                childId = entry.arguments?.getString("childId"),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.TextModeration.route) {
            TextModerationScreen(
                viewModel = textModerationViewModel,
                activeAgeProfile = activeAgeProfile,
                activeChildId = activeChildId,
                profileLoaded = ageProfileLoaded,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ImageModeration.route) {
            ImageModerationScreen(
                viewModel = imageModerationViewModel,
                activeAgeProfile = activeAgeProfile,
                activeChildId = activeChildId,
                profileLoaded = ageProfileLoaded,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ScreenTime.route) {
            ScreenTimeScreen(
                viewModel = screenTimeViewModel,
                session = session,
                activeAgeProfile = activeAgeProfile,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToChildren = { navController.navigate(Screen.Children.route) },
                onNavigateToChildProfile = { navController.navigate(Screen.ChildEditor.create(it)) }
            )
        }

        composable(Screen.AppManagement.route) {
            AppManagementScreen(
                viewModel = appManagementViewModel,
                activeAgeProfile = activeAgeProfile,
                activeChild = selectedChild,
                children = children,
                familyId = familyId,
                onNavigateToAppDetail = { appId -> navController.navigate(Screen.AppDetail.create(appId)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AppDetail.route,
            arguments = listOf(navArgument("appId") { type = NavType.StringType })
        ) { entry ->
            val appId = entry.arguments?.getString("appId") ?: ""
            AppDetailScreen(
                appId = appId,
                viewModel = appManagementViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ReviewQueue.route) {
            ReviewQueueScreen(session = session, onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.WebProtection.route) {
            WebProtectionScreen(session = session, onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Privacy.route) {
            PrivacyScreen(session = session, onNavigateBack = { navController.popBackStack() })
        }
    }
}

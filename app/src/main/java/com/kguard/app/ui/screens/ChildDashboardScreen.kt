package com.kguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.repository.ScreenTimeRepository
import com.kguard.app.data.repository.UserPreferencesRepository
import com.kguard.app.live.LiveSessionActivity
import com.kguard.app.protection.DeviceProtection
import com.kguard.app.screentime.UninstallGuard
import com.kguard.app.service.KGuardCompanionService
import com.kguard.app.ui.components.ChildAvatar
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.ProtectionSetupCard
import com.kguard.app.ui.components.KGuardLogo
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The child's own view of K-Guard: friendly, calm, legible. One gradient spotlight hero carries
 * the promise ("You're protected"); every protection fact below sits on a kit card with a 40dp
 * signal-tinted icon circle. Screen time and schedules come from what this phone last reported;
 * nothing is invented before it reports.
 */
@Composable
fun ChildDashboardScreen(
    session: com.kguard.app.viewmodel.SessionViewModel? = null,
    childProfile: ChildProfile?,
    onNavigateToProfile: () -> Unit = {},
    familyId: String? = null,
    onPairDevice: () -> Unit = {}
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    // First install: walk through every Android permission before the dashboard appears, so screen time,
    // locking, app blocking and uninstall protection actually work from day one. Once seen (or complete),
    // the dashboard's setup card takes over as a quiet fallback.
    if (!isPreview && !onboardingSeen(context) && !childPermissionsComplete(context)) {
        PermissionsOnboardingScreen(onAllDone = {}, onSkip = {})
        return
    }

    // Start the enforcement engine; it resolves the family and child itself and stops on a parent's phone.
    LaunchedEffect(Unit) {
        if (!isPreview) KGuardCompanionService.startCompanionService(context)
    }

    // Uninstall protection lives outside Compose state (DevicePolicyManager); re-check on every resume.
    var adminCheck by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { adminCheck++ }
    val adminActive = remember(adminCheck) { DeviceProtection.isAdminActive(context) }
    // The device-admin screen must be started from this activity; it will not open as a new task.
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        adminCheck++
        KGuardCompanionService.requestSync(context)
    }

    val deviceFlow = remember(familyId, childProfile?.id) {
        if (familyId.isNullOrBlank() || childProfile == null) flowOf(ChildDevice())
        else ScreenTimeRepository.device(familyId, childProfile.id, childProfile.name)
    }
    val device by deviceFlow.collectAsState(initial = ChildDevice())

    // Removal needs the parent's PIN; the companion service raises this prompt when someone tries.
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }
    val sessionBusy = session?.busy
    val sessionError = session?.error
    val removePromptRequested by UninstallGuard.promptRequested.collectAsState()
    LaunchedEffect(removePromptRequested) {
        if (removePromptRequested) {
            showRemoveDialog = true
            UninstallGuard.promptRequested.value = false
        }
    }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            KGuardTopAppBar(
                title = "K-Guard Child Protection",
                canNavigateBack = false,
                actions = {
                    IconButton(onClick = onNavigateToProfile) {
                        if (childProfile != null) {
                            ChildAvatar(childProfile, 32.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile"
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero: the single gradient spotlight card on this screen.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(KGShapes.spotlight)
                    .background(
                        Brush.linearGradient(
                            listOf(KGColors.gradientViolet, KGColors.accent)
                        )
                    )
                    .padding(24.dp)
            ) {
                KGuardLogo(modifier = Modifier.size(56.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = childProfile?.let { "You're protected, ${it.name}" }
                        ?: "Welcome to K-Guard",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (childProfile != null) "This phone is watched over by K-Guard."
                        else "This phone isn't paired with your parent's K-Guard app yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f)
                )
                if (childProfile != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(KGShapes.pill)
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "VPN firewall & AI safety active",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            if (childProfile == null) {
                KGCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Pair this phone", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Ask your parent for the pairing code from their K-Guard app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    KGPrimaryButton(text = "Pair this phone", onClick = onPairDevice, modifier = Modifier.fillMaxWidth())
                }
            }

            if (!isPreview) ProtectionSetupCard()

            // The child asks for an app right here: pick from this phone, the parent approves remotely.
            if (!isPreview && childProfile != null && session != null) {
                var showRequestApp by rememberSaveable { mutableStateOf(false) }
                val sessionBusy by session.busy.collectAsState()
                val sessionError by session.error.collectAsState()
                KGCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Want a new app?", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Pick one from this phone and your parent gets a request to allow it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    KGPrimaryButton(
                        text = "Request an app",
                        leading = { Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { showRequestApp = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (showRequestApp) {
                    RequestAppDialog(
                        session = session,
                        childId = childProfile.id,
                        busy = sessionBusy,
                        error = sessionError,
                        onDismiss = { showRequestApp = false }
                    )
                }
            }

            KGSectionHeader(
                title = "Protection on this phone",
                hint = "What K-Guard does for you every day"
            )

            ProtectionFactCard(
                icon = Icons.Default.Timer,
                tint = KGColors.accent,
                title = "Screen Time Today",
                description = screenTimeText(device)
            )
            ProtectionFactCard(
                icon = Icons.Default.Bedtime,
                tint = KGColors.gradientViolet,
                title = "Schedules",
                description = scheduleText(device)
            )
            ProtectionFactCard(
                icon = Icons.Default.Apps,
                tint = KGColors.warning,
                title = "App Access & Web Firewall",
                description = "Your parent's protection rules block harmful sites and apps on this device."
            )
            if (adminActive) {
                ProtectionFactCard(
                    icon = Icons.Default.Lock,
                    tint = KGColors.success,
                    title = "Uninstall Protection Active",
                    description = "This device is supervised by K-Guard. The app cannot be removed while protection is on."
                )
            } else {
                // Weakened state: the card itself is the fix — tapping launches system activation.
                ProtectionFactCard(
                    icon = Icons.Default.Security,
                    tint = KGColors.warning,
                    title = "Uninstall protection is off",
                    description = "Tap to turn it back on so this app cannot be removed.",
                    onClick = { runCatching { adminLauncher.launch(DeviceProtection.activationIntent(context)) } }
                )
            }
            ProtectionFactCard(
                icon = Icons.Default.Visibility,
                tint = KGColors.info,
                title = "K-Guard Live",
                description = "Tap to see what is protecting this phone right now.",
                onClick = {
                    KGuardCompanionService.startCompanionService(context)
                    context.startActivity(Intent(context, LiveSessionActivity::class.java))
                }
            )

            KGTextLink(
                text = "Remove K-Guard from this phone (parent PIN)",
                onClick = { showRemoveDialog = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    if (showRemoveDialog) {
        val prefs = remember { UserPreferencesRepository(context.applicationContext) }
        RemoveKGuardDialog(
            verify = { pin -> prefs.verifyParentPin(pin) },
            onDismiss = { showRemoveDialog = false },
            onVerified = {
                showRemoveDialog = false
                scope.launch { UninstallGuard.releaseAndUninstall(context) }
            }
        )
    }
}

private fun screenTimeText(device: ChildDevice): String {
    val used = ScreenTimeRepository.formatDuration(device.usedScreenTimeMinutes)
    return when {
        !device.hasReported -> "Measuring… finish the setup steps above if this doesn't update."
        device.isLocked -> device.lockReason?.takeIf { it.isNotBlank() } ?: device.lockState.label
        device.effectiveLimitMinutes > 0 ->
            "$used used · ${ScreenTimeRepository.formatDuration(device.remainingMinutes)} left today"
        else -> "$used used today · no daily limit"
    }
}

private fun scheduleText(device: ChildDevice): String = buildList {
    if (device.bedtimeEnabled) add("Bedtime ${device.bedtimeStart}–${device.bedtimeEnd}")
    if (device.schoolEnabled) add("School hours ${device.schoolStart}–${device.schoolEnd} (games, video and social apps paused)")
}.joinToString("\n").ifEmpty { "No bedtime or school hours set." }

/** Asks for the parent's PIN before K-Guard may be removed; attempts are rate-limited by UninstallGuard. */
@Composable
private fun RemoveKGuardDialog(verify: suspend (String) -> Boolean, onDismiss: () -> Unit, onVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun submit() {
        val wait = UninstallGuard.lockoutRemaining(context)
        if (wait > 0) {
            error = "Too many tries. Wait ${(wait + 999) / 1000} seconds."
            return
        }
        checking = true
        scope.launch {
            val ok = verify(pin)
            checking = false
            if (ok) {
                UninstallGuard.recordSuccess(context)
                onVerified()
            } else {
                pin = ""
                val left = UninstallGuard.recordFailure(context)
                error = if (left == 0) "Too many tries. Wait ${UninstallGuard.LOCKOUT_MS / 1000} seconds."
                    else "That PIN isn't right. $left ${if (left == 1) "try" else "tries"} left."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parent PIN required", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Removing K-Guard switches off this phone's protection, and your parent will be told. After the PIN, tap Uninstall on the next screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KGColors.inkMuted
                )
                KGTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter(Char::isDigit).take(8); error = null },
                    label = "Parent PIN",
                    isError = error != null,
                    supportingText = error,
                    keyboardType = KeyboardType.NumberPassword,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            KGPrimaryButton(text = "Continue", onClick = ::submit, enabled = pin.length >= 4 && !checking)
        },
        dismissButton = { KGTextLink(text = "Cancel", onClick = onDismiss) }
    )
}

/** One protection fact: kit card, 40dp signal-tinted icon circle, calm two-line copy. */
@Composable
private fun ProtectionFactCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    description: String,
    onClick: (() -> Unit)? = null
) {
    KGCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(KGShapes.circle)
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChildDashboardPreview() {
    KGuardTheme {
        ChildDashboardScreen(childProfile = ChildProfile(name = "Ama"))
    }
}

/** One installed launchable app, for the child's own "request an app" picker. */
private data class InstalledApp(val label: String, val packageName: String)

/**
 * The child picks one of THIS phone's installed apps and sends an approval request to the parent,
 * who allows it (with a limit) from app management. Requests go through the Cloud Function.
 */
@Composable
private fun RequestAppDialog(
    session: com.kguard.app.viewmodel.SessionViewModel,
    childId: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Labels and packages come from the child's own launcher list; K-Guard itself is excluded.
    val installed = remember {
        val pm = context.packageManager
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { info ->
                    (info.loadLabel(pm) as? String)?.takeIf { it.isNotBlank() }?.let { InstalledApp(it, info.activityInfo.packageName) }
                }
                .distinctBy { it.packageName }
                .filter { it.packageName != context.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<InstalledApp?>(null) }
    var sent by remember { mutableStateOf(false) }

    val matches = installed.filter { it.label.contains(query.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request an app", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sent) {
                    Text(
                        "Request sent! Your parent gets a notification and can allow it with a daily limit.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "Choose one of the apps on this phone. Your parent is asked to allow it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KGTextField(value = query, onValueChange = { query = it }, label = "Search apps")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (picked == null) {
                            matches.take(40).forEach { app ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { picked = app }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (matches.isEmpty()) Text(
                                "No app matches \"$query\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text("Send a request for:", style = MaterialTheme.typography.labelLarge)
                            Text(picked!!.label, style = MaterialTheme.typography.titleMedium)
                            error?.let { Text(it, color = KGColors.danger, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                sent -> KGPrimaryButton(text = "Done", onClick = onDismiss)
                picked != null -> KGPrimaryButton(
                    text = if (busy) "Sending…" else "Send request",
                    enabled = !busy,
                    onClick = {
                        session.requestAppInstall(
                            childId = childId,
                            appName = picked!!.label,
                            packageName = picked!!.packageName
                        )
                        sent = true
                    }
                )
            }
        },
        dismissButton = { KGTextLink(text = if (sent) "Close" else "Cancel", onClick = onDismiss) }
    )
}

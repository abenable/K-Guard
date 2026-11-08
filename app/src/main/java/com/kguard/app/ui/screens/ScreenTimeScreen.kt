package com.kguard.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.model.DeviceLockState
import com.kguard.app.data.model.RemoteAction
import com.kguard.app.data.model.RemoteCommand
import com.kguard.app.data.model.ScreenTimeConfig
import com.kguard.app.data.repository.AppActivity
import com.kguard.app.data.repository.ScreenTimeRepository
import com.kguard.app.ui.components.AppActivitySection
import com.kguard.app.ui.components.ChildAvatar
import com.kguard.app.ui.components.ChildPhoneHealthCard
import com.kguard.app.ui.components.PhoneLockDialog
import com.kguard.app.ui.components.TimeRangeDialog
import com.kguard.app.ui.components.lastSeenText
import com.kguard.app.ui.components.KGChip
import com.kguard.app.ui.components.KGDangerButton
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGEmptyState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGStatusTag
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGToggleRow
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.ScreenTimeViewModel
import com.kguard.app.viewmodel.SessionViewModel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScreenTimeScreen(
    viewModel: ScreenTimeViewModel,
    session: SessionViewModel? = null,
    activeAgeProfile: AgeProfile = AgeProfile.PRETEENS_8_12,
    onNavigateBack: () -> Unit,
    onNavigateToChildren: () -> Unit = {},
    onNavigateToChildProfile: (String) -> Unit = {}
) {
    val device by viewModel.currentDevice.collectAsState()
    val commandHistory by viewModel.commandHistory.collectAsState()
    val appActivity by viewModel.appActivity.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val feedbackMessage by viewModel.actionFeedback.collectAsState()

    val childrenFromSession = session?.children?.collectAsState()?.value
    val selectedChildFromSession = session?.selectedChild?.collectAsState()?.value
    val familyId = session?.familyId?.collectAsState()?.value

    val children = childrenFromSession.orEmpty()
    val activeChild = selectedChildFromSession ?: children.firstOrNull()

    // Follow the session's child; the first time a parent opens a child, write age-based starting rules.
    LaunchedEffect(familyId, activeChild?.id) {
        val child = activeChild ?: return@LaunchedEffect
        viewModel.setFamilyAndChild(familyId, child.id, child.name)
        viewModel.initializeDefaultsIfNeeded(child.age)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    // Key on the feedback id (not the message string) so repeated identical actions re-show the snackbar.
    LaunchedEffect(feedbackMessage?.id) {
        feedbackMessage?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.clearFeedback()
        }
    }

    if (children.isEmpty()) {
        Scaffold(
            topBar = {
                KGuardTopAppBar(
                    title = "Child Screen Management",
                    canNavigateBack = true,
                    onNavigateBack = onNavigateBack
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            // Honest empty state: without a child there is no telemetry and no family
            // to command, so the whole dashboard stays hidden instead of showing zeros.
            KGEmptyState(
                icon = Icons.Default.Devices,
                title = "No children yet",
                description = "Add a child and pair their phone to see screen time and send remote commands.",
                action = { KGPrimaryButton("Go to children", onClick = onNavigateToChildren) },
                modifier = Modifier.padding(innerPadding)
            )
        }
        return
    }

    // The dashboard brings its own Scaffold (top bar + snackbar host).
    ScreenTimeDashboardContent(
        device = device,
        children = children,
        selectedChildId = device.childId,
        activeAgeProfile = activeAgeProfile,
        commandHistory = commandHistory,
        appActivity = appActivity,
        busy = busy,
        onSelectChild = { child ->
            viewModel.selectChild(child.id, child.name)
            session?.selectChild(child.id)
        },
        onNavigateToChildProfile = onNavigateToChildProfile,
        onRemoteSwitchOff = { reason -> viewModel.remoteSwitchOff(device.childId, reason) },
        onRemoteLock = { startsIn, duration ->
            if (startsIn == 0 && duration == null) {
                viewModel.remoteSwitchOff(device.childId, "")
            } else {
                val total = duration ?: 0
                viewModel.remoteTimedLock(device.childId, total / 60, total % 60, startsIn.takeIf { it > 0 })
            }
        },
        onRemoteUnlock = { viewModel.remoteUnlock(device.childId) },
        onRemoteSoundAlarm = { viewModel.remoteSoundAlarm(device.childId) },
        onStartLive = { viewModel.startLiveCheck(device.childId) },
        onAddBonusTime = { minutes -> viewModel.addBonusTime(device.childId, minutes) },
        onUpdateDailyLimit = { limit -> viewModel.updateDailyLimit(device.childId, limit) },
        onApplyAgeRecommendation = { viewModel.applyAgeRecommendation(device.childId, activeAgeProfile) },
        onToggleBedtime = { enabled -> viewModel.updateBedtimeSchedule(device.childId, device.bedtimeStart, device.bedtimeEnd, enabled) },
        onToggleSchoolMode = { enabled -> viewModel.updateSchoolSchedule(device.childId, device.schoolStart, device.schoolEnd, enabled) },
        onChangeBedtime = { start, end -> viewModel.updateBedtimeSchedule(device.childId, start, end, true) },
        onChangeSchoolHours = { start, end -> viewModel.updateSchoolSchedule(device.childId, start, end, true) },
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState
    )
}

@Composable
fun ScreenTimeDashboardContent(
    device: ChildDevice,
    children: List<ChildProfile>,
    selectedChildId: String,
    activeAgeProfile: AgeProfile,
    commandHistory: List<RemoteCommand>,
    onSelectChild: (ChildProfile) -> Unit,
    onRemoteSwitchOff: (String) -> Unit,
    onRemoteUnlock: () -> Unit,
    onRemoteSoundAlarm: () -> Unit,
    onStartLive: () -> Unit = {},
    onAddBonusTime: (Int) -> Unit,
    onUpdateDailyLimit: (Int) -> Unit,
    onApplyAgeRecommendation: () -> Unit,
    onToggleBedtime: (Boolean) -> Unit,
    onToggleSchoolMode: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    appActivity: List<AppActivity> = emptyList(),
    busy: Boolean = false,
    onNavigateToChildProfile: (String) -> Unit = {},
    /** Lock from the "later / for a set time" dialog: delay before it starts (0 = now), length (null = until unlocked). */
    onRemoteLock: (startsInMinutes: Int, durationMinutes: Int?) -> Unit = { _, _ -> },
    onChangeBedtime: (start: String, end: String) -> Unit = { _, _ -> },
    onChangeSchoolHours: (start: String, end: String) -> Unit = { _, _ -> }
) {
    var showSwitchOffDialog by rememberSaveable { mutableStateOf(false) }
    var showLockDialog by rememberSaveable { mutableStateOf(false) }
    var selectedReason by rememberSaveable { mutableStateOf("Dinner Time") }
    var customReason by rememberSaveable { mutableStateOf("") }
    var activeTab by rememberSaveable { mutableIntStateOf(0) } // 0: Controls & Status, 1: Apps & Breakdown, 2: Schedules, 3: History

    Scaffold(
        topBar = {
            KGuardTopAppBar(
                title = "Child Screen Management",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Saving to the child's rules; action buttons are disabled meanwhile.
            if (busy) {
                LinearProgressIndicator(
                    color = KGColors.accent,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 1. Connected Children Device Switcher (tap the selected child again for their profile)
            ChildrenDeviceSelector(
                children = children,
                selectedChildId = selectedChildId,
                onSelectChild = onSelectChild,
                onOpenProfile = { onNavigateToChildProfile(it.id) }
            )

            Column(modifier = Modifier.padding(16.dp)) {

                // 2. Connected Phone Telemetry & Live Status Card
                ConnectedPhoneStatusCard(
                    device = device,
                    child = children.firstOrNull { it.id == selectedChildId },
                    busy = busy,
                    onOpenProfile = { if (device.childId.isNotBlank()) onNavigateToChildProfile(device.childId) },
                    onSoundAlarm = onRemoteSoundAlarm
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Can the child's phone actually enforce the rules?
                ChildPhoneHealthCard(device = device)

                Spacer(modifier = Modifier.height(16.dp))

                // 3. The screen's single gradient spotlight: the remote switch-off control.
                RemoteSwitchOffHeroCard(
                    device = device,
                    busy = busy,
                    onOpenSwitchOffDialog = { showSwitchOffDialog = true },
                    onOpenLockDialog = { showLockDialog = true },
                    onRemoteUnlock = onRemoteUnlock,
                    onAddBonusTime = onAddBonusTime,
                    onStartLive = onStartLive
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Row for Deep Management
                TabRow(
                    selectedTabIndex = activeTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Screen Limits", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Apps Today", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        text = { Text("Schedules", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = activeTab == 3,
                        onClick = { activeTab = 3 },
                        text = { Text("Command Log", fontWeight = FontWeight.SemiBold) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (activeTab) {
                    0 -> {
                        // Daily Screen Time Progress & Limit Controls
                        DailyLimitManagementSection(
                            device = device,
                            activeAgeProfile = activeAgeProfile,
                            busy = busy,
                            onUpdateDailyLimit = onUpdateDailyLimit,
                            onApplyAgeRecommendation = onApplyAgeRecommendation
                        )
                    }
                    1 -> {
                        // Live Apps Breakdown
                        AppUsageBreakdownSection(device = device)
                        Spacer(modifier = Modifier.height(8.dp))
                        AppActivitySection(activity = appActivity, childName = device.childName.ifBlank { "your child" })
                    }
                    2 -> {
                        // Schedules & Focus Modes
                        SchedulesSection(
                            device = device,
                            onToggleBedtime = onToggleBedtime,
                            onToggleSchoolMode = onToggleSchoolMode,
                            onChangeBedtime = onChangeBedtime,
                            onChangeSchoolHours = onChangeSchoolHours,
                            busy = busy
                        )
                    }
                    3 -> {
                        // Audit Log of Remote Commands
                        RemoteCommandHistorySection(commandHistory = commandHistory)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Switch Off Confirmation Dialog
    if (showSwitchOffDialog) {
        val quickReasons = listOf("Dinner Time", "Bedtime / Sleep", "Study & Homework", "Screen Time Exceeded")
        AlertDialog(
            onDismissRequest = { showSwitchOffDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(KGShapes.circle)
                        .background(KGColors.danger.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = KGColors.danger,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Switch off ${device.childName}'s screen?",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        text = "This turns off the screen and locks ${device.childName.ifBlank { "your child" }}'s phone until you unlock it. Emergency calls always work.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "REASON FOR LOCKOUT",
                        style = MaterialTheme.typography.labelSmall,
                        color = KGColors.inkMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    quickReasons.forEach { reason ->
                        KGChip(
                            reason,
                            selectedReason == reason,
                            onClick = { selectedReason = reason },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    KGTextField(
                        value = customReason,
                        onValueChange = { customReason = it },
                        label = "Or add a custom note",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                KGDangerButton(
                    text = "Switch off now",
                    enabled = !busy,
                    onClick = {
                        val finalReason = customReason.trim().ifEmpty { selectedReason }
                        onRemoteSwitchOff(finalReason)
                        showSwitchOffDialog = false
                    }
                )
            },
            dismissButton = {
                KGTextLink(text = "Cancel", onClick = { showSwitchOffDialog = false })
            }
        )
    }

    // Lock later and/or for a set time.
    if (showLockDialog) {
        PhoneLockDialog(
            childName = device.childName.ifBlank { "your child" },
            onDismiss = { showLockDialog = false },
            onConfirm = { startsIn, duration ->
                onRemoteLock(startsIn, duration)
                showLockDialog = false
            }
        )
    }
}

// ---------------- UI COMPONENTS ----------------

@Composable
fun ChildrenDeviceSelector(
    children: List<ChildProfile>,
    selectedChildId: String,
    onSelectChild: (ChildProfile) -> Unit,
    onOpenProfile: (ChildProfile) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        children.forEach { child ->
            val isSelected = child.id == selectedChildId
            Surface(
                onClick = { if (isSelected) onOpenProfile(child) else onSelectChild(child) },
                shape = KGShapes.pill,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChildAvatar(child = child, size = 22.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = child.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                    // NOTE: no per-child online indicator here — the ViewModel only exposes live
                    // telemetry for the currently selected child, so fabricating presence for the
                    // other children would be dishonest. The status card shows real online state.
                }
            }
        }
    }
}

@Composable
fun ConnectedPhoneStatusCard(
    device: ChildDevice,
    child: ChildProfile? = null,
    busy: Boolean = false,
    onOpenProfile: () -> Unit = {},
    onSoundAlarm: () -> Unit
) {
    val batteryKnown = device.batteryPercent >= 0
    val batteryColor = if (device.batteryPercent > 20) KGColors.success else KGColors.danger
    val lockStateColor = when {
        !device.hasReported -> KGColors.inkMuted
        device.lockState == DeviceLockState.REMOTE_LOCKED -> KGColors.danger
        device.lockState == DeviceLockState.ACTIVE_UNLOCKED -> KGColors.success
        else -> KGColors.warning
    }
    val childName = device.childName.ifBlank { child?.name ?: "Child" }
    val details = listOf(device.deviceModel, device.androidVersion).filter { it.isNotBlank() }.joinToString(" · ")

    KGCard(modifier = Modifier.fillMaxWidth()) {
        // Header: identity (tap for the child's profile) + live connectivity
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(KGShapes.cardSmall)
                    .clickable(onClick = onOpenProfile),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChildAvatar(name = childName, photoUrl = child?.photoUrl.orEmpty(), size = 42.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = device.deviceName.ifBlank { "$childName's phone" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            !device.hasReported -> "Not connected yet"
                            !device.isOnline -> "Last seen ${lastSeenText(device)}"
                            else -> details.ifBlank { "Connected" }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = KGColors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            KGStatusTag(
                text = when {
                    !device.hasReported -> "Not connected"
                    device.isOnline -> "Online"
                    else -> "Offline"
                },
                color = if (device.isOnline) KGColors.success else KGColors.inkMuted
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (!device.hasReported) {
            // No invented telemetry: battery, network and pickups only appear once the phone reports.
            Text(
                text = "$childName's phone hasn't reported yet. Battery, network and screen time appear here once K-Guard is set up on it.",
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted
            )
        } else {
            // Battery row with level bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (device.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                        contentDescription = null,
                        tint = if (batteryKnown) batteryColor else KGColors.inkMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Battery", style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted)
                }
                Text(
                    text = if (batteryKnown) "${device.batteryPercent}%${if (device.isCharging) " · charging" else ""}" else "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (batteryKnown) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { device.batteryPercent / 100f },
                    color = batteryColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(KGShapes.pill)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Network + Pickups telemetry
            Row(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Network", style = MaterialTheme.typography.labelSmall, color = KGColors.inkMuted)
                        Text(
                            device.networkName.ifBlank { "Unknown" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Pickups", style = MaterialTheme.typography.labelSmall, color = KGColors.inkMuted)
                        Text(
                            "${device.pickupsCount} today",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live activity strip: what the phone last reported it is enforcing.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KGShapes.cardSmall,
            color = lockStateColor.copy(alpha = 0.08f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(KGShapes.circle)
                        .background(lockStateColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        !device.hasReported -> "Waiting for the phone to connect"
                        device.lockState == DeviceLockState.REMOTE_LOCKED -> "Screen is switched off · locked remotely"
                        device.lockState == DeviceLockState.ACTIVE_UNLOCKED ->
                            device.currentForegroundApp?.let { "Unlocked · using $it" } ?: "Unlocked"
                        else -> device.lockState.label
                    } + if (device.hasReported && !device.isOnline) " (when last seen)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = lockStateColor,
                    modifier = Modifier.weight(1f)
                )

                // Sound chime action
                KGTextLink("Ring phone", onClick = onSoundAlarm, enabled = !busy)
            }
        }
    }
}

/**
 * The screen's single gradient spotlight card (DESIGN.md allows one per screen): the remote
 * switch-off control, violet→magenta with white ink and an inverse white pill CTA.
 */
@Composable
fun RemoteSwitchOffHeroCard(
    device: ChildDevice,
    busy: Boolean = false,
    onOpenSwitchOffDialog: () -> Unit,
    onOpenLockDialog: () -> Unit = {},
    onRemoteUnlock: () -> Unit,
    onAddBonusTime: (Int) -> Unit,
    onStartLive: () -> Unit = {}
) {
    // Locked as reported by the phone, or a remote lock the parent set that the phone hasn't applied yet.
    val isLocked = device.isLocked || device.screenLock.isLocked
    val scheduleLock = device.lockState == DeviceLockState.BEDTIME_LOCKED || device.lockState == DeviceLockState.LIMIT_REACHED
    val name = device.childName.ifBlank { "your child" }
    fun hm(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
    val now = System.currentTimeMillis()
    // Bedtime and limit reasons already say when they end; only a parent's timed lock needs the time added.
    val untilText = if (!scheduleLock) {
        (device.lockUntilMillis ?: device.remoteLockUntilMillis)?.takeIf { it > now }?.let { " · until ${hm(it)}" } ?: ""
    } else ""
    val pausedUntil = device.overrideUntilMillis?.takeIf { it > now }
    val title = when {
        !isLocked -> "Remote screen switch-off"
        device.lockState == DeviceLockState.BEDTIME_LOCKED -> "Bedtime lock is on"
        device.lockState == DeviceLockState.LIMIT_REACHED -> "Daily limit reached"
        device.lockState == DeviceLockState.SCHOOL_LOCKED -> "School hours are on"
        device.lockState == DeviceLockState.REMOTE_LOCKED -> "Device switched off & locked"
        else -> "Lock sent · waiting for the phone"
    }
    val subtitle = when {
        isLocked -> "“${device.lockReason ?: device.screenLock.lockReason ?: "Parental lock"}”$untilText"
        pausedUntil != null -> "Bedtime and the daily limit are paused until ${hm(pausedUntil)}"
        else -> "Turn off the screen now, later, or for a set time"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(KGColors.gradientViolet, KGColors.gradientMagenta)
                ),
                shape = KGShapes.spotlight
            )
            .padding(20.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(KGShapes.circle)
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isLocked) Icons.Default.Lock else Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inverse white pill — the spotlight card's paired CTA per DESIGN.md.
            Surface(
                onClick = if (isLocked) onRemoteUnlock else onOpenSwitchOffDialog,
                enabled = !busy,
                shape = KGShapes.pill,
                color = Color.White.copy(alpha = if (busy) 0.6f else 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isLocked) Icons.Default.LockOpen else Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            !isLocked -> "Switch off phone"
                            scheduleLock -> "Unlock for 1 hour"
                            else -> "Unlock & resume $name's phone"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (isLocked && scheduleLock) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unlocking pauses bedtime and the daily limit for an hour, then the normal rules apply again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Secondary actions — translucent pills on the spotlight ground.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpotlightPill(
                    text = "Live check",
                    icon = Icons.Default.Visibility,
                    enabled = !busy,
                    onClick = onStartLive,
                    modifier = Modifier.weight(1f)
                )
                if (!isLocked) {
                    SpotlightPill(
                        text = "Later or timed",
                        icon = Icons.Default.Schedule,
                        enabled = !busy,
                        onClick = onOpenLockDialog,
                        modifier = Modifier.weight(1f)
                    )
                }
                listOf(15, 30).forEach { mins ->
                    SpotlightPill(
                        text = "+$mins min",
                        icon = Icons.Default.MoreTime,
                        enabled = !busy,
                        onClick = { onAddBonusTime(mins) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Translucent pill on the gradient spotlight card. */
@Composable
private fun SpotlightPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = KGShapes.pill,
        color = Color.White.copy(alpha = 0.16f),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DailyLimitManagementSection(
    device: ChildDevice,
    activeAgeProfile: AgeProfile,
    busy: Boolean = false,
    onUpdateDailyLimit: (Int) -> Unit,
    onApplyAgeRecommendation: () -> Unit
) {
    val fmt = ScreenTimeRepository::formatDuration
    val limit = device.effectiveLimitMinutes
    val hasLimit = limit > 0
    KGSectionHeader(
        title = "Daily screen time",
        hint = "Set how long ${device.childName} can use the device today."
    )

    KGCard(modifier = Modifier.fillMaxWidth()) {
        // Usage header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Today's screen time", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = when {
                    !device.hasReported -> "Not reported"
                    hasLimit -> "${fmt(device.usedScreenTimeMinutes)} / ${fmt(limit)}"
                    else -> fmt(device.usedScreenTimeMinutes)
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (device.hasReported && hasLimit && device.remainingMinutes == 0) KGColors.danger else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Visual progress bar — signal colour only as the day runs out.
        val progress = device.usageProgress
        if (!device.hasReported || !hasLimit) {
            Text(
                text = when {
                    !device.hasReported -> "${device.childName.ifBlank { "Your child" }}'s phone hasn't reported screen time yet."
                    else -> "No daily limit · screen time is measured but not capped."
                },
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(KGShapes.pill)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(10.dp)
                        .clip(KGShapes.pill)
                        .background(
                            when {
                                progress >= 1f -> KGColors.danger
                                progress >= 0.8f -> KGColors.warning
                                else -> KGColors.success
                            }
                        )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${fmt(device.remainingMinutes)} left today" +
                        if (device.bonusMinutesToday > 0) " · includes ${fmt(device.bonusMinutesToday)} bonus" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
                Text(
                    text = "${(progress * 100).toInt()}% used",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Slider — holds a local pending value during the drag and only commits to the
        // repository on release, avoiding a Firestore write batch per drag step and the
        // thumb snap-back during round-trips.
        var pendingLimit by remember(device.dailyLimitMinutes) { mutableStateOf<Float?>(null) }
        val sliderValue = pendingLimit ?: device.dailyLimitMinutes.toFloat()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Daily limit", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (sliderValue.toInt() <= 0) "No limit" else fmt(sliderValue.toInt()),
                style = MaterialTheme.typography.titleMedium
            )
        }
        // 0 (far left) = no limit, then 15-minute steps up to 6 hours.
        Slider(
            value = sliderValue,
            onValueChange = { pendingLimit = it },
            onValueChangeFinished = {
                pendingLimit?.let { onUpdateDailyLimit(it.toInt()) }
                pendingLimit = null
            },
            enabled = !busy,
            valueRange = 0f..360f,
            steps = 23
        )

        // Quick preset limit chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0, 45, 60, 90, 120, 150, 180).forEach { mins ->
                val isSelected = device.dailyLimitMinutes == mins
                Surface(
                    shape = KGShapes.pill,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clip(KGShapes.pill)
                        .clickable(enabled = !busy) { onUpdateDailyLimit(mins) }
                ) {
                    Text(
                        text = if (mins == 0) "No limit" else "${mins}m",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Age-Aware Recommendation
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KGShapes.cardSmall,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "K-Guard pediatric recommendation",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ScreenTimeConfig.getRecommendationForAge(activeAgeProfile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                KGSecondaryButton(
                    text = "Apply recommended limit",
                    onClick = onApplyAgeRecommendation,
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
fun AppUsageBreakdownSection(device: ChildDevice) {
    KGSectionHeader(
        title = "Apps today",
        hint = "Where ${device.childName}'s screen time went."
    )

    KGCard(modifier = Modifier.fillMaxWidth()) {
        if (device.appUsages.isEmpty()) {
            Text(
                text = if (device.hasReported) "No app usage recorded yet today."
                else "${device.childName.ifBlank { "Your child" }}'s phone hasn't reported app usage yet.",
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted
            )
        } else {
            // Bars show progress toward an app's own limit, or its share next to the most-used app.
            val mostUsed = device.appUsages.maxOf { it.usedMinutes }.coerceAtLeast(1)
            device.appUsages.forEach { app ->
                val appLimit = app.dailyLimitMinutes?.takeIf { it > 0 }
                val appProgress = (app.usedMinutes.toFloat() / (appLimit ?: mostUsed)).coerceIn(0f, 1f)

                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(KGShapes.cardSmall)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = app.appName.take(1),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    if (app.isCurrentlyActive) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        KGStatusTag("Active", KGColors.success)
                                    }
                                    if (app.isBlocked) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        KGStatusTag("Blocked", KGColors.danger)
                                    }
                                }
                                Text(
                                    app.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KGColors.inkMuted
                                )
                            }
                        }
                        Text(
                            text = ScreenTimeRepository.formatDuration(app.usedMinutes) +
                                (appLimit?.let { " / ${ScreenTimeRepository.formatDuration(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(KGShapes.pill)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(appProgress)
                                .height(6.dp)
                                .clip(KGShapes.pill)
                                .background(
                                    if (appLimit != null && appProgress >= 1f) KGColors.danger else MaterialTheme.colorScheme.primary
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SchedulesSection(
    device: ChildDevice,
    onToggleBedtime: (Boolean) -> Unit,
    onToggleSchoolMode: (Boolean) -> Unit,
    onChangeBedtime: (start: String, end: String) -> Unit = { _, _ -> },
    onChangeSchoolHours: (start: String, end: String) -> Unit = { _, _ -> },
    busy: Boolean = false
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // "bedtime" | "school"
    val name = device.childName.ifBlank { "Your child" }
    val pausedUntil = device.overrideUntilMillis?.takeIf { it > System.currentTimeMillis() }

    KGSectionHeader(
        title = "Schedules",
        hint = "Automatic downtime and focus windows."
    )

    // Bedtime card
    KGCard(modifier = Modifier.fillMaxWidth()) {
        KGToggleRow(
            title = "Bedtime downtime",
            description = "${device.bedtimeStart} to ${device.bedtimeEnd}",
            checked = device.bedtimeEnabled,
            onCheckedChange = onToggleBedtime,
            enabled = !busy
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Every night the screen switches off and the phone stays locked until the end time. " +
                "$name gets a warning 10 minutes before. Emergency calls stay available.",
            style = MaterialTheme.typography.bodySmall,
            color = KGColors.inkMuted
        )
        if (pausedUntil != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Paused until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil))} after you unlocked the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.warning
            )
        }
        KGTextLink("Change times", onClick = { editing = "bedtime" })
    }

    Spacer(modifier = Modifier.height(12.dp))

    // School Mode card
    KGCard(modifier = Modifier.fillMaxWidth()) {
        KGToggleRow(
            title = "School hours focus",
            description = "${device.schoolStart} to ${device.schoolEnd}",
            checked = device.schoolEnabled,
            onCheckedChange = onToggleSchoolMode,
            enabled = !busy
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Monday to Friday, games, video, music and social apps are paused during these hours. " +
                "Calls, messages, maps and learning apps keep working.",
            style = MaterialTheme.typography.bodySmall,
            color = KGColors.inkMuted
        )
        KGTextLink("Change times", onClick = { editing = "school" })
    }

    when (editing) {
        "bedtime" -> TimeRangeDialog(
            title = "Bedtime",
            start = device.bedtimeStart,
            end = device.bedtimeEnd,
            note = if (device.bedtimeEnabled) null else "Saving turns bedtime on.",
            onDismiss = { editing = null }
        ) { start, end ->
            onChangeBedtime(start, end)
            editing = null
        }
        "school" -> TimeRangeDialog(
            title = "School hours",
            start = device.schoolStart,
            end = device.schoolEnd,
            note = if (device.schoolEnabled) "Applies Monday to Friday." else "Applies Monday to Friday. Saving turns school hours on.",
            onDismiss = { editing = null }
        ) { start, end ->
            onChangeSchoolHours(start, end)
            editing = null
        }
    }
}

/** Signal colour for a command row, by action type. */
@Composable
private fun commandActionColor(action: RemoteAction): Color = when (action) {
    RemoteAction.SWITCH_OFF_LOCK -> KGColors.danger
    RemoteAction.UNLOCK_DEVICE -> KGColors.success
    RemoteAction.SOUND_ALARM -> KGColors.warning
    RemoteAction.ADD_BONUS_TIME -> KGColors.gradientViolet
    RemoteAction.TOGGLE_BEDTIME, RemoteAction.TOGGLE_SCHOOL_MODE, RemoteAction.SET_DAILY_LIMIT -> KGColors.accent
    else -> KGColors.inkMuted
}

/**
 * Signal colour for a command's delivery status, as the repository words it:
 * "Sent · waiting for the phone", "Delivered", "Not delivered · phone was offline".
 */
@Composable
private fun commandStatusColor(status: String): Color {
    val s = status.lowercase()
    return when {
        s.startsWith("not") || s.contains("fail") || s.contains("error") -> KGColors.danger
        s.contains("waiting") || s.startsWith("sent") || s.contains("pend") -> KGColors.warning
        s.startsWith("delivered") -> KGColors.success
        else -> KGColors.inkMuted
    }
}

@Composable
fun RemoteCommandHistorySection(commandHistory: List<RemoteCommand>) {
    KGSectionHeader(
        title = "Command log",
        hint = "History of remote lockouts, screen time bonuses, and device actions."
    )

    KGCard(modifier = Modifier.fillMaxWidth()) {
        if (commandHistory.isEmpty()) {
            Text(
                text = "No remote commands sent yet.",
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted
            )
        } else {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val dateFormat = remember(locale) { SimpleDateFormat("HH:mm · MMM d", locale) }
            commandHistory.forEach { cmd ->
                val actionTint = commandActionColor(cmd.action)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(KGShapes.circle)
                            .background(actionTint.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            when (cmd.action) {
                                RemoteAction.SWITCH_OFF_LOCK -> Icons.Default.PowerSettingsNew
                                RemoteAction.UNLOCK_DEVICE -> Icons.Default.LockOpen
                                RemoteAction.SOUND_ALARM -> Icons.Default.VolumeUp
                                RemoteAction.ADD_BONUS_TIME -> Icons.Default.MoreTime
                                RemoteAction.SET_DAILY_LIMIT -> Icons.Default.Timer
                                RemoteAction.TOGGLE_BEDTIME -> Icons.Default.Bedtime
                                RemoteAction.TOGGLE_SCHOOL_MODE -> Icons.Default.School
                                else -> Icons.Default.History
                            },
                            contentDescription = null,
                            tint = actionTint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = cmd.description, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = dateFormat.format(Date(cmd.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = KGColors.inkMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    KGStatusTag(text = cmd.status, color = commandStatusColor(cmd.status))
                }
            }
        }
    }
}

// ---------------- BACKWARD COMPATIBLE SCREEN TIME CONTENT PREVIEW ----------------

@Preview(showBackground = true)
@Composable
fun ScreenTimeDashboardPreview() {
    KGuardTheme {
        ScreenTimeDashboardContent(
            device = ChildDevice(
                deviceId = "dev_1",
                childId = "c1",
                childName = "Ama",
                deviceName = "Ama's Galaxy A14",
                deviceModel = "Samsung Galaxy A14 5G",
                androidVersion = "Android 14",
                isOnline = true,
                lastSeenMinutesAgo = 0,
                lastSyncedAtMillis = 0L,
                batteryPercent = 78,
                networkName = "Home Wi-Fi (5 GHz)",
                currentForegroundApp = "YouTube Kids",
                usedScreenTimeMinutes = 45,
                dailyLimitMinutes = 90
            ),
            children = listOf(ChildProfile("c1", "Ama")),
            selectedChildId = "c1",
            activeAgeProfile = AgeProfile.PRETEENS_8_12,
            commandHistory = emptyList(),
            onSelectChild = {},
            onRemoteSwitchOff = {},
            onRemoteUnlock = {},
            onRemoteSoundAlarm = {},
            onAddBonusTime = {},
            onUpdateDailyLimit = {},
            onApplyAgeRecommendation = {},
            onToggleBedtime = {},
            onToggleSchoolMode = {},
            onNavigateBack = {}
        )
    }
}

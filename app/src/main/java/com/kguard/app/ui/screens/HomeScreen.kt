package com.kguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.repository.ScreenTimeRepository
import com.kguard.app.ui.components.ChildAvatar
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGStatusTag
import com.kguard.app.ui.components.KGuardLogo
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.components.QuickActionsFab
import com.kguard.app.ui.components.QuickActionsScrim
import com.kguard.app.ui.components.parentQuickActions
import com.kguard.app.ui.components.rememberQuickActionsState
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import java.util.Calendar
import kotlinx.coroutines.flow.flowOf

@Composable
fun HomeScreen(
    activeAgeProfile: AgeProfile,
    onNavigateToTextModeration: () -> Unit,
    onNavigateToImageModeration: () -> Unit,
    onNavigateToAgeProfile: () -> Unit,
    onNavigateToScreenTime: () -> Unit,
    onNavigateToAppManagement: () -> Unit,
    onNavigateToFlaggedContent: () -> Unit,
    activeChildName: String? = null,
    parentName: String? = null,
    openAlertCount: Int = 0,
    pendingApprovalCount: Int = 0,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToWebProtection: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToChildren: () -> Unit = {},
    children: List<ChildProfile> = emptyList(),
    selectedChild: ChildProfile? = null,
    familyId: String? = null,
    onSelectChild: (String) -> Unit = {},
    onAddChild: () -> Unit = {}
) {
    var quickActionsOpen by rememberQuickActionsState()
    Scaffold(
        floatingActionButton = {
            QuickActionsFab(
                actions = parentQuickActions(
                    pendingApprovals = pendingApprovalCount,
                    onWebProtection = onNavigateToWebProtection,
                    onAppApprovals = onNavigateToAppManagement,
                    onScreenTime = onNavigateToScreenTime,
                    onAddChild = onAddChild
                ),
                expanded = quickActionsOpen,
                onExpandedChange = { quickActionsOpen = it }
            )
        },
        topBar = {
            KGuardTopAppBar(
                title = "K-Guard Dashboard",
                canNavigateBack = false,
                actions = {
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
      Box(Modifier.fillMaxSize().padding(innerPadding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Greeting block: display-tier headline + active child context. Taps to the
            // age-profile screen, preserving the previous hero card's navigation.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToAgeProfile() }
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = greeting(),
                        style = MaterialTheme.typography.labelMedium,
                        color = KGColors.inkMuted
                    )
                    // The signed-in parent's name, not the child's — the child belongs on the subtitle line.
                    Text(
                        text = parentName?.takeIf { it.isNotBlank() } ?: "Parent",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            if (activeChildName != null) append("Watching over $activeChildName · ")
                            append("${activeAgeProfile.title} (${activeAgeProfile.ageRange}) · Tap to change")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = KGColors.inkMuted
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Change Profile",
                    tint = KGColors.inkMuted
                )
            }

            val heroChild = selectedChild ?: children.firstOrNull()
            if (heroChild != null) {
                Spacer(modifier = Modifier.height(20.dp))
                ScreenTimeHeroCard(
                    child = heroChild,
                    children = children,
                    familyId = familyId,
                    onSelectChild = onSelectChild,
                    onManageChildren = onNavigateToChildren,
                    onClick = onNavigateToScreenTime
                )
            }

            KGSectionHeader(
                title = "Dashboard",
                hint = if (activeChildName != null) {
                    "Everything watching over $activeChildName in one place"
                } else {
                    "Every family-safety tool in one place"
                }
            )

            // 2-column tool grid: Children first (previously unreachable from here).
            val cards = listOf(
                DashboardCardData(
                    title = "Children",
                    description = "Manage child profiles & pairing.",
                    icon = Icons.Default.FamilyRestroom,
                    tint = KGColors.gradientOrange,
                    onClick = onNavigateToChildren
                ),
                DashboardCardData(
                    title = "Screen Time",
                    description = "Live telemetry, app breakdown, and remote switch-off.",
                    icon = Icons.Default.Timer,
                    tint = KGColors.accent,
                    onClick = onNavigateToScreenTime
                ),
                DashboardCardData(
                    title = "App Control",
                    description = if (pendingApprovalCount > 0) {
                        "$pendingApprovalCount approval request(s) waiting."
                    } else {
                        "Review requests, safety scores, and app limits."
                    },
                    icon = Icons.Default.Apps,
                    tint = KGColors.gradientViolet,
                    onClick = onNavigateToAppManagement
                ),
                DashboardCardData(
                    title = "Web Protection",
                    description = "Block harmful websites in every browser and app.",
                    icon = Icons.Default.Public,
                    tint = KGColors.success,
                    onClick = onNavigateToWebProtection
                ),
                DashboardCardData(
                    title = "Review Queue",
                    description = "Audit flagged content and record your decisions.",
                    icon = Icons.Default.NotificationsActive,
                    tint = if (openAlertCount > 0) KGColors.danger else KGColors.warning,
                    openAlertCount = openAlertCount,
                    onClick = onNavigateToFlaggedContent
                ),
                DashboardCardData(
                    title = "Moderate Text",
                    description = "Scan messages, comments, and web terms for age safety.",
                    icon = Icons.Default.Description,
                    tint = KGColors.gradientMagenta,
                    onClick = onNavigateToTextModeration
                ),
                DashboardCardData(
                    title = "Moderate Images",
                    description = "Automatic warnings, blur previews, or blocks on photos.",
                    icon = Icons.Default.Image,
                    tint = KGColors.gradientCoral,
                    onClick = onNavigateToImageModeration
                ),
                DashboardCardData(
                    title = "Privacy",
                    description = "Alert preferences, quiet hours, and data retention.",
                    icon = Icons.Default.Lock,
                    tint = KGColors.inkMuted,
                    onClick = onNavigateToPrivacy
                )
            )
            cards.chunked(2).forEach { rowCards ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowCards.forEach { card ->
                        DashboardToolCard(
                            data = card,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The single gradient spotlight allowed on this screen: a slim brand banner.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(KGColors.gradientOrange, KGColors.gradientCoral)
                        ),
                        shape = KGShapes.card
                    )
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KGuardLogo(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "K-Guard combines Screen Time, App Control, and AI Content Moderation for complete digital family safety.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
            // Room for the + button so the banner isn't hidden behind it.
            Spacer(modifier = Modifier.height(88.dp))
        }
        QuickActionsScrim(quickActionsOpen) { quickActionsOpen = false }
      }
    }
}

/**
 * The selected child's day at a glance, from what their phone last reported. Tapping opens Screen
 * Time; the name opens a child switcher when there is more than one child. Nothing is invented: until
 * the phone reports, the card says so.
 */
@Composable
private fun ScreenTimeHeroCard(
    child: ChildProfile,
    children: List<ChildProfile>,
    familyId: String?,
    onSelectChild: (String) -> Unit,
    onManageChildren: () -> Unit,
    onClick: () -> Unit
) {
    val flow = remember(familyId, child.id) {
        if (familyId.isNullOrBlank()) flowOf(ChildDevice()) else ScreenTimeRepository.device(familyId, child.id, child.name)
    }
    val collected by flow.collectAsState(initial = ChildDevice())
    // Until the newly selected child's first snapshot arrives, don't show the previous child's numbers.
    val device = collected.takeIf { it.childId == child.id } ?: ChildDevice()
    var switcherOpen by remember { mutableStateOf(false) }

    KGCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChildAvatar(child, 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (children.size > 1) Modifier.clickable { switcherOpen = true } else Modifier
                    ) {
                        Text(child.name, style = MaterialTheme.typography.titleMedium)
                        if (children.size > 1) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Switch child",
                                tint = KGColors.inkMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(expanded = switcherOpen, onDismissRequest = { switcherOpen = false }) {
                        children.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name, style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { ChildAvatar(c, 28.dp) },
                                onClick = {
                                    switcherOpen = false
                                    onSelectChild(c.id)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Manage children", color = KGColors.accent, style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                switcherOpen = false
                                onManageChildren()
                            }
                        )
                    }
                }
                Text(
                    text = listOfNotNull(
                        device.deviceModel.ifBlank { device.deviceName }.takeIf { it.isNotBlank() },
                        syncedText(device)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted,
                    maxLines = 1
                )
            }
            when {
                !device.hasReported -> KGStatusTag("Not connected", KGColors.warning)
                device.isOnline -> KGStatusTag("Online", KGColors.success)
                else -> KGStatusTag("Offline", KGColors.inkMuted)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Screen time today", style = MaterialTheme.typography.labelMedium, color = KGColors.inkMuted)

        if (!device.hasReported) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${child.name}'s phone hasn't reported yet. Screen time shows here once K-Guard is set up on it.",
                style = MaterialTheme.typography.bodyMedium,
                color = KGColors.inkMuted
            )
            return@KGCard
        }

        val limit = device.effectiveLimitMinutes
        Text(
            ScreenTimeRepository.formatDuration(device.usedScreenTimeMinutes),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (limit > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { device.usageProgress },
                color = if (device.isLocked) KGColors.danger else KGColors.accent,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (limit > 0) "${ScreenTimeRepository.formatDuration(device.remainingMinutes)} left" else "No daily limit",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (limit > 0) {
                Text(
                    "of ${ScreenTimeRepository.formatDuration(limit)} today",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
            }
        }

        val status = listOfNotNull(
            if (device.isLocked) device.lockReason?.takeIf { it.isNotBlank() } ?: device.lockState.label else null,
            if (device.bedtimeEnabled) "Bedtime ${device.bedtimeStart}–${device.bedtimeEnd}" else null,
            if (device.schoolEnabled) "School hours ${device.schoolStart}–${device.schoolEnd}" else null
        )
        if (status.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                status.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (device.isLocked) KGColors.danger else KGColors.inkMuted
            )
        }
    }
}

private fun syncedText(device: ChildDevice): String {
    if (!device.hasReported) return "Not reported yet"
    val mins = device.lastSeenMinutesAgo
    return when {
        mins < 0 -> "Synced"
        mins < 1 -> "Synced just now"
        mins < 60 -> "Synced $mins min ago"
        mins < 24 * 60 -> "Synced ${mins / 60}h ago"
        else -> "Synced ${mins / (24 * 60)}d ago"
    }
}

private fun greeting(): String {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}

private data class DashboardCardData(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val tint: Color,
    val openAlertCount: Int = 0,
    val onClick: () -> Unit
)

/** One dashboard tool tile: 40dp tinted icon circle, title, muted description, optional alert tag. */
@Composable
private fun DashboardToolCard(
    data: DashboardCardData,
    modifier: Modifier = Modifier
) {
    KGCard(modifier = modifier, onClick = data.onClick) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, KGShapes.circle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = data.icon,
                contentDescription = null,
                tint = data.tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = data.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = data.description,
            style = MaterialTheme.typography.bodySmall,
            color = KGColors.inkMuted
        )
        if (data.openAlertCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            KGStatusTag(
                text = "${data.openAlertCount} open",
                color = KGColors.danger
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    KGuardTheme {
        HomeScreen(
            activeAgeProfile = AgeProfile.PRETEENS_8_12,
            activeChildName = "Ama",
            children = listOf(ChildProfile(id = "1", name = "Ama"), ChildProfile(id = "2", name = "Kofi")),
            selectedChild = ChildProfile(id = "1", name = "Ama"),
            openAlertCount = 2,
            pendingApprovalCount = 1,
            onNavigateToTextModeration = {},
            onNavigateToImageModeration = {},
            onNavigateToAgeProfile = {},
            onNavigateToScreenTime = {},
            onNavigateToAppManagement = {},
            onNavigateToFlaggedContent = {}
        )
    }
}

package com.kguard.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.model.AppAccessInfo
import com.kguard.app.data.model.AppApprovalStatus
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.ui.components.ChildAvatar
import com.kguard.app.ui.components.KGDangerButton
import com.kguard.app.ui.components.KGChip
import com.kguard.app.ui.components.KGEmptyState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGStatusTag
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.AppManagementViewModel

@Composable
fun AppManagementScreen(
    viewModel: AppManagementViewModel,
    activeAgeProfile: AgeProfile,
    activeChild: ChildProfile? = null,
    children: List<ChildProfile> = emptyList(),
    familyId: String? = null,
    onNavigateToAppDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val managedApps by viewModel.managedApps.collectAsState()
    val appIcons by viewModel.appIcons.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()

    // Load every child's apps; the child chips below narrow the list on screen.
    LaunchedEffect(familyId) {
        viewModel.setFamilyAndChild(familyId, null)
    }

    AppManagementContent(
        managedApps = managedApps,
        activeAgeProfile = activeAgeProfile,
        activeChild = activeChild,
        children = children,
        busy = busy,
        error = error,
        message = message,
        onClearMessage = { viewModel.clearMessage() },
        onClearError = { viewModel.clearError() },
        onApprove = { appId -> viewModel.approveApp(familyId, appId) },
        onReject = { appId -> viewModel.rejectApp(familyId, appId) },
        onUpdateLimit = { appId, limit -> viewModel.updateAppLimit(familyId, appId, limit) },
        onRequestInstall = { childId, name, pkg, cat, minAge, limit ->
            viewModel.requestAppInstall(familyId, childId, name, pkg, cat, minAge, limit)
        },
        onNavigateToAppDetail = onNavigateToAppDetail,
        onNavigateBack = onNavigateBack
    )
}

/** Safety-score tier colour: >=80 success, 50–79 warning, <50 danger. */
@Composable
private fun safetyScoreColor(score: Int): androidx.compose.ui.graphics.Color = when {
    score >= 80 -> KGColors.success
    score >= 50 -> KGColors.warning
    else -> KGColors.danger
}

@Composable
fun AppManagementContent(
    managedApps: List<AppAccessInfo>,
    activeAgeProfile: AgeProfile,
    activeChild: ChildProfile? = null,
    children: List<ChildProfile> = emptyList(),
    busy: Boolean = false,
    error: String? = null,
    message: String? = null,
    onClearMessage: () -> Unit = {},
    onClearError: () -> Unit = {},
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onUpdateLimit: (String, Int) -> Unit = { _, _ -> },
    onRequestInstall: (String, String, String, String, Int, Int) -> Unit = { _, _, _, _, _, _ -> },
    onNavigateToAppDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    var selectedFilter by rememberSaveable { mutableStateOf<AppApprovalStatus?>(null) }
    // Keyed on the active child so a navigation to a different child can't surface a stale selection.
    var selectedChildId by rememberSaveable(activeChild?.id) { mutableStateOf<String?>(activeChild?.id) }
    var showRequestDialog by rememberSaveable { mutableStateOf(false) }
    // Store the app id (saveable primitive) and derive the object, instead of holding an object.
    var editingAppId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingAppLimit = managedApps.firstOrNull { it.id == editingAppId }
    // Non-null while the top-bar search field is open.
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val query = searchQuery.orEmpty().trim()

    // Child + search scope; the status chip counts follow it so they match the list underneath.
    val scopedApps = managedApps.filter { app ->
        val childMatches = selectedChildId == null || app.childId.isEmpty() || app.childId == selectedChildId
        val searchMatches = query.isEmpty() ||
            app.appName.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
        childMatches && searchMatches
    }
    fun countOf(status: AppApprovalStatus) = scopedApps.count { it.approvalStatus == status }

    // The header badge stays family-wide (or per chosen child), independent of search.
    val pendingCount = managedApps.count {
        it.approvalStatus == AppApprovalStatus.PENDING_REVIEW &&
            (selectedChildId == null || it.childId.isEmpty() || it.childId == selectedChildId)
    }

    val filteredApps = scopedApps.filter { selectedFilter == null || it.approvalStatus == selectedFilter }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onClearMessage()
        }
    }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            onClearError()
        }
    }

    Scaffold(
        topBar = {
            KGuardTopAppBar(
                title = "App Management & Approvals",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSearchClose = { searchQuery = null },
                searchPlaceholder = "Search apps or packages",
                actions = {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Search apps")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showRequestDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Request App") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Screen header & pending badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PILLAR 2: APP CONTROLS",
                        style = MaterialTheme.typography.labelSmall,
                        color = KGColors.inkMuted
                    )
                    Text(
                        text = "Install Approvals & Suitability",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (pendingCount > 0) {
                    KGStatusTag(
                        text = "$pendingCount Pending",
                        color = KGColors.warning
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Child filter row (shown even for a single child so it's clear whose apps these are)
            if (children.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    item {
                        KGChip(
                            "All Children",
                            selectedChildId == null,
                            onClick = { selectedChildId = null },
                            leading = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    items(children) { c ->
                        KGChip(
                            c.name,
                            selectedChildId == c.id,
                            onClick = { selectedChildId = c.id },
                            leading = { ChildAvatar(c, 20.dp) }
                        )
                    }
                }
            }

            // Approval Status Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                item {
                    KGChip("All (${scopedApps.size})", selectedFilter == null, onClick = { selectedFilter = null })
                }
                item {
                    KGChip(
                        "Needs Approval (${countOf(AppApprovalStatus.PENDING_REVIEW)})",
                        selectedFilter == AppApprovalStatus.PENDING_REVIEW,
                        onClick = { selectedFilter = AppApprovalStatus.PENDING_REVIEW }
                    )
                }
                item {
                    KGChip(
                        "Approved (${countOf(AppApprovalStatus.APPROVED)})",
                        selectedFilter == AppApprovalStatus.APPROVED,
                        onClick = { selectedFilter = AppApprovalStatus.APPROVED }
                    )
                }
                item {
                    KGChip(
                        "Rejected (${countOf(AppApprovalStatus.REJECTED)})",
                        selectedFilter == AppApprovalStatus.REJECTED,
                        onClick = { selectedFilter = AppApprovalStatus.REJECTED }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (busy) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (managedApps.isEmpty()) {
                        // No family data or no apps yet: the honest empty state.
                        KGEmptyState(
                            icon = Icons.Default.Apps,
                            title = "No apps yet",
                            description = "Apps your child installs will appear here for approval."
                        )
                    } else if (query.isNotEmpty()) {
                        KGEmptyState(
                            icon = Icons.Default.Search,
                            title = "No matches",
                            description = "No apps match \"$query\"."
                        )
                    } else {
                        // Apps exist, but the active filter/child selection excludes them all.
                        KGEmptyState(
                            icon = Icons.Default.Apps,
                            title = "No apps match this filter",
                            description = "Try a different approval status or child to see more apps."
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredApps, key = { "${it.packageName}-${it.hashCode()}" }) { app ->
                        ManagedAppCard(
                            app = app,
                            busy = busy,
                            appIcons = appIcons,
                            onApprove = { onApprove(app.id) },
                            onReject = { onReject(app.id) },
                            onEditLimit = { editingAppId = app.id },
                            onNavigateToDetail = { onNavigateToAppDetail(app.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Dialog for requesting an app
    if (showRequestDialog) {
        RequestAppDialog(
            children = children,
            activeChildId = selectedChildId ?: children.firstOrNull()?.id ?: "",
            onDismiss = { showRequestDialog = false },
            onSubmit = { childId, name, pkg, cat, minAge, limit ->
                onRequestInstall(childId, name, pkg, cat, minAge, limit)
                showRequestDialog = false
            }
        )
    }

    // Dialog for editing daily limit (state survives rotation via the saveable editingAppId)
    editingAppLimit?.let { app ->
        var limitSlider by remember { mutableFloatStateOf(app.dailyLimitMinutes.toFloat()) }
        AlertDialog(
            onDismissRequest = { editingAppId = null },
            title = { Text("Set Daily Limit for ${app.appName}") },
            text = {
                Column {
                    Text("Choose maximum daily screen time for this application:")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "${limitSlider.toInt()} minutes",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = limitSlider,
                        onValueChange = { limitSlider = it },
                        valueRange = 10f..180f,
                        steps = 17
                    )
                }
            },
            confirmButton = {
                KGPrimaryButton(
                    text = "Save Limit",
                    onClick = {
                        onUpdateLimit(app.id, limitSlider.toInt())
                        editingAppId = null
                    }
                )
            },
            dismissButton = {
                KGTextLink(
                    text = "Cancel",
                    onClick = { editingAppId = null }
                )
            }
        )
    }
}

@Composable
private fun ManagedAppCard(
    app: AppAccessInfo,
    busy: Boolean = false,
    appIcons: Map<String, android.graphics.Bitmap> = emptyMap(),
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEditLimit: () -> Unit,
    onNavigateToDetail: () -> Unit
) {
    val scoreColor = safetyScoreColor(app.safetyScore)

    KGCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onNavigateToDetail
    ) {
        // Header: icon placeholder + name + meta + safety score
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, KGShapes.circle),
                    contentAlignment = Alignment.Center
                ) {
                    // The real icon when the child's phone has uploaded one; a letter avatar otherwise.
                    val icon = appIcons[app.packageName]
                    if (icon != null) {
                        Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = app.appName,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = app.appName.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = buildString {
                            append(app.category)
                            append(" • ")
                            append(app.packageName)
                            if (app.childName.isNotBlank()) append(" • For ${app.childName}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = KGColors.inkMuted
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            KGStatusTag(
                text = "Safety ${app.safetyScore}",
                color = scoreColor
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Block switch: on = blocked (rejected), off = allowed (approved).
            Switch(
                checked = app.approvalStatus == AppApprovalStatus.REJECTED,
                onCheckedChange = { block -> if (block) onReject() else onApprove() },
                enabled = !busy
            )
        }

        // Safety reason / suitability details
        if (app.safetyReason.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, KGShapes.cardSmall)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = app.safetyReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Daily limit row + status chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEditLimit() }
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = KGColors.inkMuted
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (app.dailyLimitMinutes > 0) "Daily limit: ${app.dailyLimitMinutes}m" else "No daily limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!app.installed) {
                KGStatusTag(text = "Uninstalled", color = KGColors.inkMuted)
                Spacer(modifier = Modifier.width(6.dp))
            }
            KGStatusTag(
                text = when (app.approvalStatus) {
                    AppApprovalStatus.APPROVED -> "APPROVED"
                    AppApprovalStatus.PENDING_REVIEW -> "PENDING REVIEW"
                    AppApprovalStatus.REJECTED -> "REJECTED"
                },
                color = when (app.approvalStatus) {
                    AppApprovalStatus.APPROVED -> KGColors.success
                    AppApprovalStatus.PENDING_REVIEW -> KGColors.warning
                    AppApprovalStatus.REJECTED -> KGColors.danger
                }
            )
        }

        // Pending apps get explicit Approve / Decline; approved and rejected apps
        // are toggled with the block switch above (it replaces the old Revoke button).
        if (app.approvalStatus == AppApprovalStatus.PENDING_REVIEW) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                KGSecondaryButton(
                    text = "Approve",
                    onClick = onApprove,
                    enabled = !busy,
                    leading = {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                KGDangerButton(
                    text = "Decline",
                    onClick = onReject,
                    enabled = !busy
                )
            }
        }
    }
}

@Composable
private fun RequestAppDialog(
    children: List<ChildProfile>,
    activeChildId: String,
    onDismiss: () -> Unit,
    onSubmit: (childId: String, name: String, pkg: String, cat: String, minAge: Int, limit: Int) -> Unit
) {
    var selectedChild by remember { mutableStateOf(activeChildId.ifEmpty { children.firstOrNull()?.id ?: "" }) }
    var appName by remember { mutableStateOf("Roblox") }
    var packageName by remember { mutableStateOf("com.roblox.client") }
    var category by remember { mutableStateOf("Gaming & Community") }
    var minAgeRating by remember { mutableIntStateOf(10) }
    var dailyLimit by remember { mutableIntStateOf(45) }

    val presets = listOf(
        Triple("Roblox", "com.roblox.client", "Gaming & Community") to 10,
        Triple("Minecraft", "com.mojang.minecraftpe", "Gaming & Community") to 7,
        Triple("TikTok", "com.zhiliaoapp.musically", "Social Media") to 13,
        Triple("YouTube Kids", "com.google.android.apps.youtube.kids", "Video & Entertainment") to 3,
        Triple("Duolingo ABC", "com.duolingo.abc", "Education") to 3
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request an App", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Sent from the child device — the request goes to the parent for review and approval before the app can be used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Quick presets",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presets) { (preset, age) ->
                        KGChip(
                            preset.first,
                            appName == preset.first,
                            onClick = {
                                appName = preset.first
                                packageName = preset.second
                                category = preset.third
                                minAgeRating = age
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                KGTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = "App Name",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                KGTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = "Package Name",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KGTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = "Category",
                        modifier = Modifier.weight(1f)
                    )
                    KGTextField(
                        value = minAgeRating.toString(),
                        onValueChange = { minAgeRating = it.toIntOrNull() ?: 3 },
                        label = "Min Age",
                        modifier = Modifier.width(88.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    "Daily Limit: ${dailyLimit} minutes",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
                Slider(
                    value = dailyLimit.toFloat(),
                    onValueChange = { dailyLimit = it.toInt() },
                    valueRange = 15f..180f,
                    steps = 10
                )
            }
        },
        confirmButton = {
            KGPrimaryButton(
                text = "Send Request",
                onClick = {
                    onSubmit(selectedChild, appName, packageName, category, minAgeRating, dailyLimit)
                },
                enabled = appName.isNotBlank() && packageName.isNotBlank()
            )
        },
        dismissButton = {
            KGTextLink(
                text = "Cancel",
                onClick = onDismiss
            )
        }
    )
}

@Preview(showBackground = true)
@Composable
fun AppManagementScreenPreview() {
    KGuardTheme {
        AppManagementContent(
            managedApps = listOf(
                AppAccessInfo("1", "Roblox", "com.roblox.client", "Gaming & Community", 10, 68, AppApprovalStatus.PENDING_REVIEW, 30, "Contains unmoderated user chat."),
                AppAccessInfo("2", "Duolingo", "com.duolingo", "Education", 4, 95, AppApprovalStatus.APPROVED, 60, "Safe educational app with high safety rating.")
            ),
            activeAgeProfile = AgeProfile.PRETEENS_8_12,
            onApprove = {},
            onReject = {},
            onNavigateBack = {}
        )
    }
}

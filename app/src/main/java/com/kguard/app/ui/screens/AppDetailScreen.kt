package com.kguard.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.AppAccessInfo
import com.kguard.app.data.model.AppApprovalStatus
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGDangerButton
import com.kguard.app.ui.components.KGEmptyState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGStatusTag
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.AppManagementViewModel

/** Safety-score tier colour: >=80 success, 50–79 warning, <50 danger. */
@Composable
private fun safetyScoreColor(score: Int): androidx.compose.ui.graphics.Color = when {
    score >= 80 -> KGColors.success
    score >= 50 -> KGColors.warning
    else -> KGColors.danger
}

/** Safety-score tier label for the status tag. */
private fun safetyScoreLabel(score: Int) = when {
    score >= 80 -> "Safe"
    score >= 50 -> "Caution"
    else -> "High risk"
}

@Composable
fun AppDetailScreen(
    appId: String,
    viewModel: AppManagementViewModel,
    onNavigateBack: () -> Unit
) {
    val managedApps by viewModel.managedApps.collectAsState()
    // No fabricated placeholder: an unknown id shows the honest empty state instead.
    val app = managedApps.find { it.id == appId }

    AppDetailContent(
        app = app,
        onApprove = { viewModel.approveApp(appId = appId) },
        onReject = { viewModel.rejectApp(appId = appId) },
        onUpdateLimit = { limit -> viewModel.updateAppLimit(appId = appId, limitMinutes = limit) },
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun AppDetailContent(
    app: AppAccessInfo?,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onUpdateLimit: (Int) -> Unit,
    onNavigateBack: () -> Unit
) {
    // Which decision is awaiting confirmation: "approve", "reject" or null.
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            KGuardTopAppBar(
                title = app?.appName?.ifBlank { "App details" } ?: "App details",
                canNavigateBack = true,
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        if (app == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                KGEmptyState(
                    icon = Icons.Default.Apps,
                    title = "App not found",
                    description = "This app is no longer in your family's managed apps list.",
                    action = {
                        KGSecondaryButton(text = "Go back", onClick = onNavigateBack)
                    }
                )
            }
            return@Scaffold
        }

        var limitMinutes by remember(app.dailyLimitMinutes) { mutableIntStateOf(app.dailyLimitMinutes) }
        val scoreColor = safetyScoreColor(app.safetyScore)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App header card
            KGCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, KGShapes.circle),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.appName.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "${app.category} • Minimum age ${app.minAgeRating}+",
                            style = MaterialTheme.typography.bodySmall,
                            color = KGColors.inkMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = KGColors.inkMuted
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
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

                // Safety score: large number + tier-coloured bar
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "Safety score",
                            style = MaterialTheme.typography.labelSmall,
                            color = KGColors.inkMuted
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${app.safetyScore}",
                                style = MaterialTheme.typography.displaySmall,
                                color = scoreColor
                            )
                            Text(
                                text = "/100",
                                style = MaterialTheme.typography.labelMedium,
                                color = KGColors.inkMuted,
                                modifier = Modifier.padding(bottom = 5.dp, start = 4.dp)
                            )
                        }
                    }
                    KGStatusTag(
                        text = safetyScoreLabel(app.safetyScore),
                        color = scoreColor
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { app.safetyScore / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(KGShapes.pill),
                    color = scoreColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }

            // AI safety analysis
            KGCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "AI Safety & Suitability Analysis",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = app.safetyReason.ifBlank {
                        "App has been reviewed by K-Guard safety intelligence engine for age appropriateness."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Daily limit configuration
            KGCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = KGColors.inkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "App Daily Time Limit",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = if (limitMinutes > 0) "$limitMinutes minutes" else "No daily limit",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Slider(
                    value = limitMinutes.toFloat(),
                    onValueChange = { limitMinutes = it.toInt() },
                    valueRange = 0f..240f,
                    steps = 15
                )
                Text(
                    text = "Slide to adjust allowed daily usage time for ${app.appName}. All the way left removes the limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
                Spacer(modifier = Modifier.height(12.dp))
                KGSecondaryButton(
                    text = "Save Limit",
                    onClick = { onUpdateLimit(limitMinutes) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Approval actions
            KGCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Access Control & Approval",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    KGDangerButton(
                        text = "Reject / Revoke",
                        onClick = { confirming = "reject" },
                        modifier = Modifier.weight(1f)
                    )
                    KGPrimaryButton(
                        text = "Approve",
                        onClick = { confirming = "approve" },
                        modifier = Modifier.weight(1f),
                        leading = {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (app != null && confirming != null) {
        val approving = confirming == "approve"
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (approving) "Approve ${app.appName}?" else "Reject ${app.appName}?") },
            text = {
                Text(
                    if (approving) "Your child will be able to install and use ${app.appName} within the daily limit you set."
                    else "${app.appName} will be blocked on your child's phone. You can approve it again later."
                )
            },
            confirmButton = {
                if (approving) {
                    KGPrimaryButton(text = "Approve", onClick = { onApprove(); confirming = null })
                } else {
                    KGDangerButton(text = "Reject", onClick = { onReject(); confirming = null })
                }
            },
            dismissButton = {
                KGTextLink(text = "Cancel", onClick = { confirming = null })
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AppDetailPreview() {
    KGuardTheme {
        AppDetailContent(
            app = AppAccessInfo(
                appName = "Roblox",
                category = "Gaming & Community",
                minAgeRating = 10,
                safetyScore = 68,
                approvalStatus = AppApprovalStatus.PENDING_REVIEW,
                safetyReason = "Contains unmoderated user chat."
            ),
            onApprove = {},
            onReject = {},
            onUpdateLimit = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AppDetailEmptyPreview() {
    KGuardTheme {
        AppDetailContent(
            app = null,
            onApprove = {},
            onReject = {},
            onUpdateLimit = {},
            onNavigateBack = {}
        )
    }
}

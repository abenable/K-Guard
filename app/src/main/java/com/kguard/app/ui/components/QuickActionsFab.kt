package com.kguard.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes

/** One shortcut in the [QuickActionsFab] menu. */
data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val badge: Int = 0,
    val onClick: () -> Unit
)

/** The parent dashboard's shortcuts, top first. Icons match the dashboard's tool tiles. */
fun parentQuickActions(
    pendingApprovals: Int,
    onWebProtection: () -> Unit,
    onAppApprovals: () -> Unit,
    onScreenTime: () -> Unit,
    onAddChild: () -> Unit
) = listOf(
    QuickAction("Web protection", Icons.Default.Public, onClick = onWebProtection),
    QuickAction("App approvals", Icons.Default.Apps, badge = pendingApprovals, onClick = onAppApprovals),
    QuickAction("Screen time", Icons.Default.Timer, onClick = onScreenTime),
    QuickAction("Add a child", Icons.Default.PersonAdd, onClick = onAddChild)
)

/**
 * A black + button (the primary-pill colour) that opens labelled shortcuts above it. Use
 * [QuickActionsScrim] as a full-screen layer and this as the Scaffold's FAB, sharing one state.
 * Back closes the menu.
 */
@Composable
fun QuickActionsFab(
    actions: List<QuickAction>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fab-rotation")

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        actions.forEach { action ->
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                val pick = {
                    onExpandedChange(false)
                    action.onClick()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Surface(
                        shape = KGShapes.pill,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable(onClick = pick)
                    ) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    SmallFloatingActionButton(
                        onClick = pick,
                        shape = KGShapes.circle,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        if (action.badge > 0) {
                            BadgedBox(badge = {
                                Badge(containerColor = KGColors.danger, contentColor = Color.White) {
                                    Text(if (action.badge > 99) "99+" else "${action.badge}")
                                }
                            }) { Icon(action.icon, contentDescription = action.label) }
                        } else {
                            Icon(action.icon, contentDescription = action.label)
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            shape = KGShapes.circle,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.semantics {
                contentDescription = if (expanded) "Close quick actions" else "Quick actions"
            }
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.rotate(rotation))
        }
    }
}

/** Dims the page while the quick actions are open; tapping it closes them. */
@Composable
fun QuickActionsScrim(expanded: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
    }
}

/** Whether the quick actions are open, kept across configuration changes. */
@Composable
fun rememberQuickActionsState() = rememberSaveable { mutableStateOf(false) }

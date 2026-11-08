package com.kguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.ChildProfile
import com.kguard.app.data.model.ParentAction
import com.kguard.app.data.model.SafetyEvent
import com.kguard.app.ui.components.ChildAvatar
import com.kguard.app.ui.components.DecisionBadge
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGDangerButton
import com.kguard.app.ui.components.KGEmptyState
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGTextLink
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.SessionViewModel
import java.text.DateFormat

/** Live list of moderation events for the family. Parents record Allow / Keep blocked / Dismiss with a note. */
@Composable
fun ReviewQueueScreen(session: SessionViewModel, onNavigateBack: () -> Unit) {
    val events by session.events.collectAsState()
    val children by session.children.collectAsState()
    val error by session.error.collectAsState()

    ReviewQueueContent(
        events = events,
        children = children,
        error = error,
        onReview = { eventId, action, note -> session.review(eventId, action, note) },
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun ReviewQueueContent(
    events: List<SafetyEvent>,
    children: List<ChildProfile>,
    error: String?,
    onReview: (String, ParentAction, String) -> Unit,
    onNavigateBack: () -> Unit
) {
    // Filters and the in-progress review survive rotation (rememberSaveable, not remember).
    var onlyOpen by rememberSaveable { mutableStateOf(true) }
    var childFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // Non-null while the top-bar search field is open.
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    // The pending action is stored as plain ids so it survives configuration changes;
    // the SafetyEvent itself is resolved from the live list.
    var pendingEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingActionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingNote by rememberSaveable { mutableStateOf("") }

    fun clearPending() {
        pendingEventId = null
        pendingActionId = null
        pendingNote = ""
    }

    val pending: Pair<SafetyEvent, ParentAction>? = pendingEventId?.let { eid ->
        val event = events.find { it.id == eid } ?: return@let null
        val action = ParentAction.entries.find { it.id == pendingActionId } ?: return@let null
        event to action
    }

    val q = searchQuery.orEmpty().trim()
    // Child + search scope; the chip counts reflect it, the status chip then narrows it.
    val searched = events.filter { ev ->
        (childFilter == null || ev.childId == childFilter) &&
            (q.isEmpty() || listOfNotNull(ev.childName, ev.category, ev.explanation, ev.snippet).any { it.contains(q, ignoreCase = true) })
    }
    val shown = searched.filter { !onlyOpen || it.isOpen }

    Scaffold(topBar = {
        KGuardTopAppBar(
            "Alerts & Review",
            canNavigateBack = true,
            onNavigateBack = onNavigateBack,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onSearchClose = { searchQuery = null },
            searchPlaceholder = "Search alerts",
            actions = {
                IconButton(onClick = { searchQuery = "" }) {
                    Icon(Icons.Default.Search, contentDescription = "Search alerts")
                }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = onlyOpen, onClick = { onlyOpen = true }, label = { Text("Needs review (${searched.count { it.isOpen }})") })
                FilterChip(selected = !onlyOpen, onClick = { onlyOpen = false }, label = { Text("All (${searched.size})") })
            }
            if (children.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(selected = childFilter == null, onClick = { childFilter = null }, label = { Text("Everyone") })
                    children.forEach { c ->
                        FilterChip(
                            selected = childFilter == c.id,
                             onClick = { childFilter = c.id },
                            label = { Text(c.name) },
                            leadingIcon = { ChildAvatar(c, 20.dp) }
                        )
                    }
                }
            }
            error?.let { Text(it, color = KGColors.danger, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KGEmptyState(
                        icon = Icons.Default.FactCheck,
                        title = when {
                            q.isNotEmpty() -> "No matches"
                            onlyOpen -> "Nothing to review"
                            else -> "No alerts yet"
                        },
                        description = when {
                            q.isNotEmpty() -> "No alerts match \"$q\"."
                            onlyOpen -> "Nothing needs your review right now."
                            else -> "No safety events recorded yet."
                        }
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { ev ->
                        EventCard(ev, onAction = { action -> pendingEventId = ev.id; pendingActionId = action.id })
                    }
                }
            }
        }
    }

    pending?.let { (ev, action) ->
        AlertDialog(
            onDismissRequest = { clearPending() },
            title = { Text(action.label) },
            text = {
                Column {
                    Text("Add an optional note for your records (visible only to parents).")
                    KGTextField(
                        value = pendingNote,
                        onValueChange = { pendingNote = it.take(300) },
                        label = "Note (optional)",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = false
                    )
                }
            },
            confirmButton = {
                KGPrimaryButton(text = "Confirm", onClick = { onReview(ev.id, action, pendingNote); clearPending() })
            },
            dismissButton = {
                KGTextLink(text = "Cancel", onClick = { clearPending() })
            }
        )
    }
}

@Composable
private fun EventCard(ev: SafetyEvent, onAction: (ParentAction) -> Unit) {
    // Snippets are hidden until the parent chooses to read them.
    var revealed by rememberSaveable(ev.id) { mutableStateOf(false) }
    KGCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ev.childName.ifBlank { "Child" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${ev.contentType.replaceFirstChar { it.uppercase() }} · ${ev.category.replace('_', ' ')} · ${ev.context}",
                    style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted
                )
            }
            DecisionBadge(ev.parsedDecision)
        }
        Spacer(Modifier.height(8.dp))
        Text(ev.explanation, style = MaterialTheme.typography.bodyMedium)
        ev.snippet?.let {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().background(KGColors.hairline, KGShapes.cardSmall).padding(8.dp)) {
                Text(
                    "“$it”",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = if (revealed) Modifier else Modifier.blur(6.dp)
                )
            }
            KGTextLink(
                text = if (revealed) "Hide snippet" else "Reveal snippet",
                onClick = { revealed = !revealed }
            )
        }
        ev.createdAt?.let {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it.toDate()),
                style = MaterialTheme.typography.labelSmall, color = KGColors.inkMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (ev.isOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ParentAction.entries.forEach { a ->
                    when (a) {
                        ParentAction.ALLOW -> KGSecondaryButton(text = a.label, onClick = { onAction(a) })
                        ParentAction.KEEP_BLOCKED -> KGDangerButton(text = a.label, onClick = { onAction(a) })
                        ParentAction.DISMISS -> KGTextLink(text = a.label, onClick = { onAction(a) })
                    }
                }
            }
        } else {
            val done = ParentAction.entries.find { it.id == ev.parentAction }?.label ?: "Reviewed"
            Text("Reviewed: $done" + (ev.parentNote?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = KGColors.success)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ReviewQueueScreenPreview() {
    val sampleEvent = SafetyEvent(
        id = "1",
        childName = "Alex",
        contentType = "text",
        category = "cyberbullying",
        context = "Pre-Teens profile",
        decision = "WARN",
        explanation = "Contains mild teasing or insensitive language.",
        snippet = "Hey loser, you can't play with us",
        status = "open"
    )
    KGuardTheme {
        ReviewQueueContent(
            events = listOf(sampleEvent),
            children = emptyList(),
            error = null,
            onReview = { _, _, _ -> },
            onNavigateBack = {}
        )
    }
}

package com.kguard.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.FamilyPrivacy
import com.kguard.app.data.model.QuietHours
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGDangerButton
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGToggleRow
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.components.RequestPermission
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.SessionViewModel
import java.util.TimeZone

private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

@Composable
fun PrivacyScreen(session: SessionViewModel, onNavigateBack: () -> Unit) {
    val saved by session.privacy.collectAsState()
    val busy by session.busy.collectAsState()
    val user by session.user.collectAsState()
    // The form must not seed itself from defaults before the family doc (or demo data) is streaming.
    val familyId by session.familyId.collectAsState()

    PrivacyContent(
        saved = saved,
        busy = busy,
        userEmail = user?.email,
        formReady = familyId != null,
        onSavePrivacy = { session.savePrivacy(it) },
        onSignOut = { session.signOut() },
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun PrivacyContent(
    saved: FamilyPrivacy,
    busy: Boolean,
    userEmail: String?,
    formReady: Boolean = true,
    onSavePrivacy: (FamilyPrivacy) -> Unit,
    onSignOut: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var notifyOn by rememberSaveable { mutableStateOf(saved.notifyOn) }
    var includeSnippet by rememberSaveable { mutableStateOf(saved.includeSnippet) }
    var retainDays by rememberSaveable { mutableStateOf(saved.retainDays) }
    var quiet by rememberSaveable { mutableStateOf(saved.quietHours.enabled) }
    var qStart by rememberSaveable { mutableStateOf(saved.quietHours.start) }
    var qEnd by rememberSaveable { mutableStateOf(saved.quietHours.end) }
    var initialized by rememberSaveable { mutableStateOf(false) }

    // Seed the form from the remote document exactly ONCE per screen instance. Later remote
    // changes must not clobber settings the parent is mid-way through editing; the seeded
    // form fields themselves survive rotation via rememberSaveable.
    LaunchedEffect(saved, formReady) {
        if (!initialized && formReady) {
            notifyOn = saved.notifyOn
            includeSnippet = saved.includeSnippet
            retainDays = saved.retainDays
            quiet = saved.quietHours.enabled
            qStart = saved.quietHours.start
            qEnd = saved.quietHours.end
            initialized = true
        }
    }

    var notificationsDeclined by remember { mutableStateOf(false) }
    val requestNotifications = RequestPermission(Manifest.permission.POST_NOTIFICATIONS) { granted ->
        notificationsDeclined = !granted
    }

    val timesOk = !quiet || (TIME.matches(qStart) && TIME.matches(qEnd))

    Scaffold(topBar = { KGuardTopAppBar("Privacy & Notifications", canNavigateBack = true, onNavigateBack = onNavigateBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            KGSectionHeader("Which alerts should notify you?")
            listOf(
                FamilyPrivacy.NOTIFY_ALL to "Everything flagged (including Warn)",
                FamilyPrivacy.NOTIFY_WARN_AND_ABOVE to "Warn, Blur, Block and Review",
                FamilyPrivacy.NOTIFY_BLOCK_ONLY to "Only blocked content"
            ).forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = notifyOn == id, role = Role.RadioButton) { notifyOn = id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { RadioButton(selected = notifyOn == id, onClick = null); Spacer(Modifier.width(8.dp)); Text(label) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KGSecondaryButton(
                    text = "Allow notifications on this phone",
                    onClick = requestNotifications,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (notificationsDeclined) {
                    Text(
                        "Notifications were declined. You can enable them later in your phone's settings for K-Guard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            KGSectionHeader("Quiet hours")
            KGCard(Modifier.fillMaxWidth()) {
                KGToggleRow(
                    title = "Hold non-blocking alerts",
                    description = "Blocked content always notifies.",
                    checked = quiet,
                    onCheckedChange = { quiet = it }
                )
                if (quiet) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        KGTextField(
                            value = qStart,
                            onValueChange = { qStart = it.take(5) },
                            label = "From (HH:mm)",
                            modifier = Modifier.weight(1f),
                            isError = !TIME.matches(qStart),
                            supportingText = if (TIME.matches(qStart)) null else "Use HH:mm, 24-hour (e.g. 21:00)"
                        )
                        KGTextField(
                            value = qEnd,
                            onValueChange = { qEnd = it.take(5) },
                            label = "Until (HH:mm)",
                            modifier = Modifier.weight(1f),
                            isError = !TIME.matches(qEnd),
                            supportingText = if (TIME.matches(qEnd)) null else "Use HH:mm, 24-hour (e.g. 07:00)"
                        )
                    }
                }
            }

            KGSectionHeader("What K-Guard keeps")
            KGCard(Modifier.fillMaxWidth()) {
                KGToggleRow(
                    title = "Store a short text excerpt with each alert",
                    description = "Off by default. When off, alerts record the category and explanation but not the content itself, and uploaded images are deleted right after checking.",
                    checked = includeSnippet,
                    onCheckedChange = { includeSnippet = it }
                )
                Spacer(Modifier.height(8.dp))
                Text("Delete alerts after", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(7, 30, 90).forEach { d -> FilterChip(selected = retainDays == d, onClick = { retainDays = d }, label = { Text("$d days") }) }
                }
            }

            Spacer(Modifier.height(20.dp))
            KGPrimaryButton(
                text = if (busy) "Saving…" else "Save settings",
                onClick = {
                    onSavePrivacy(
                        FamilyPrivacy(
                            notifyOn = notifyOn, includeSnippet = includeSnippet, retainDays = retainDays,
                            quietHours = QuietHours(quiet, qStart, qEnd, TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
                        )
                    )
                },
                enabled = timesOk && !busy,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "Signed in as ${userEmail ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted
            )
            KGDangerButton(
                text = "Sign out",
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PrivacyScreenPreview() {
    KGuardTheme {
        PrivacyContent(
            saved = FamilyPrivacy(),
            busy = false,
            userEmail = "parent@example.com",
            onSavePrivacy = {},
            onSignOut = {},
            onNavigateBack = {}
        )
    }
}

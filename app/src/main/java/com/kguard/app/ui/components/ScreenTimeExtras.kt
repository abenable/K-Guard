package com.kguard.app.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kguard.app.data.model.ChildDevice
import com.kguard.app.data.repository.AppActivity
import com.kguard.app.screentime.ScreenPolicy
import com.kguard.app.ui.theme.KGColors
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** "just now", "12 min ago", "at 18:40" or a full date; "never" before the phone has reported. */
fun lastSeenText(device: ChildDevice): String {
    val at = device.lastSyncedAtMillis ?: return "never"
    val mins = device.lastSeenMinutesAgo
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
        else -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
    }
}

/**
 * Parent's phone: can the child's phone actually enforce the rules? Connection, what's still missing
 * on the phone, and a low battery warning, all from what the phone itself reported.
 */
@Composable
fun ChildPhoneHealthCard(device: ChildDevice, modifier: Modifier = Modifier) {
    val name = device.childName.ifBlank { "your child" }
    val missing = device.missingProtections
    val lowBattery = device.batteryPercent in 0..15 && !device.isCharging
    val (tag, tagColor) = when {
        !device.hasReported -> "Not connected" to KGColors.warning
        missing.isNotEmpty() -> "Needs attention" to KGColors.danger
        !device.isOnline -> "Offline" to KGColors.warning
        lowBattery -> "Battery low" to KGColors.warning
        else -> "All set" to KGColors.success
    }

    KGCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Child phone health",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            KGStatusTag(tag, tagColor)
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!device.hasReported) {
                HealthLine(
                    "$name's phone hasn't reported yet. Install K-Guard on it, choose Child Device Setup and enter " +
                        "the pairing code from the Children screen. Rules you set now apply as soon as it connects."
                )
                return@Column
            }
            if (!device.isOnline) {
                HealthLine(
                    "Last seen ${lastSeenText(device)}. The phone keeps enforcing the last rules it received " +
                        "and picks up changes when it reconnects.",
                    KGColors.warning
                )
            }
            if (missing.isNotEmpty()) {
                HealthLine("Still needs to be allowed on $name's phone (open K-Guard there to finish setup):")
                missing.forEach { HealthLine("• $it", KGColors.danger) }
            }
            if (lowBattery) {
                HealthLine("Battery is at ${device.batteryPercent}%. Protection stops if the phone runs flat.", KGColors.warning)
            }
            if (missing.isEmpty() && device.isOnline && !lowBattery) {
                HealthLine("Everything K-Guard needs is allowed on $name's phone.")
            }
        }
    }
}

@Composable
private fun HealthLine(text: String, color: androidx.compose.ui.graphics.Color = KGColors.inkMuted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * Edit a daily window ("21:00" to "07:00"); the end may fall on the next day.
 * [onSave] gets both times as HH:MM.
 */
@Composable
fun TimeRangeDialog(
    title: String,
    start: String,
    end: String,
    note: String? = null,
    onDismiss: () -> Unit,
    onSave: (start: String, end: String) -> Unit
) {
    var startText by remember { mutableStateOf(start) }
    var endText by remember { mutableStateOf(end) }
    val s = ScreenPolicy.parseHm(startText)
    val e = ScreenPolicy.parseHm(endText)
    val same = s != null && s == e
    val valid = s != null && e != null && !same
    fun fmt(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    KGTextField(
                        value = startText,
                        onValueChange = { if (it.length <= 5) startText = it },
                        label = "Starts (HH:MM)",
                        isError = s == null,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    KGTextField(
                        value = endText,
                        onValueChange = { if (it.length <= 5) endText = it },
                        label = "Ends (HH:MM)",
                        isError = e == null || same,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    when {
                        s == null || e == null -> "Use 24-hour time, for example 21:00."
                        same -> "Start and end can't be the same."
                        else -> "${fmt(s)} to ${fmt(e)}" + if (e < s) " (next day)" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) KGColors.inkMuted else KGColors.danger
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted)
                }
            }
        },
        confirmButton = {
            KGPrimaryButton(
                text = "Save",
                onClick = { if (s != null && e != null && valid) onSave(fmt(s), fmt(e)) },
                enabled = valid
            )
        },
        dismissButton = { KGTextLink(text = "Cancel", onClick = onDismiss) }
    )
}

/** Installs and uninstalls on the child's phone, newest first. */
@Composable
fun AppActivitySection(activity: List<AppActivity>, childName: String) {
    KGSectionHeader(
        title = "App activity",
        hint = "New apps on $childName's phone stay blocked until you approve them in App Management."
    )
    KGCard(modifier = Modifier.fillMaxWidth()) {
        if (activity.isEmpty()) {
            Text(
                "No installs or uninstalls yet.",
                style = MaterialTheme.typography.bodySmall,
                color = KGColors.inkMuted
            )
        }
        val now = System.currentTimeMillis()
        activity.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = KGColors.hairline)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    val what = when (a.type) {
                        "installed" -> when (a.status) {
                            "PENDING_REVIEW" -> "Installed · waiting for your approval"
                            "APPROVED" -> "Reinstalled · already approved"
                            "REJECTED" -> "Reinstalled · you had not approved it"
                            else -> "Installed"
                        }
                        "uninstalled" -> "Uninstalled"
                        else -> a.type
                    }
                    Text(
                        what,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            a.type == "uninstalled" -> KGColors.warning
                            a.status == "PENDING_REVIEW" -> KGColors.accent
                            else -> KGColors.inkMuted
                        }
                    )
                }
                a.atMillis?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = KGColors.inkMuted
                    )
                }
            }
        }
    }
}

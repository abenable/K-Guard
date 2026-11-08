package com.kguard.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kguard.app.ui.theme.KGColors

/**
 * Lock a child's phone: now or after a while, until the parent unlocks it or for a set time.
 * Quick picks cover the common choices in one tap; "Custom…" reveals the hour/minute fields.
 * [onConfirm] gets the delay before the lock starts (0 = now) and its length (null = until unlocked).
 */
@Composable
fun PhoneLockDialog(
    childName: String,
    onDismiss: () -> Unit,
    onConfirm: (startsInMinutes: Int, durationMinutes: Int?) -> Unit
) {
    // Start: null = now, a number = a quick pick, custom = the typed fields.
    var startQuick by remember { mutableStateOf<Int?>(null) }
    var startCustom by remember { mutableStateOf(false) }
    var startH by remember { mutableStateOf("0") }
    var startM by remember { mutableStateOf("30") }
    // Duration: null = until the parent unlocks, a number = a quick pick, custom = the typed fields.
    var durationQuick by remember { mutableStateOf<Int?>(null) }
    var durationCustom by remember { mutableStateOf(false) }
    var forH by remember { mutableStateOf("1") }
    var forM by remember { mutableStateOf("0") }

    fun minutes(h: String, m: String): Int? {
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh * 60 + mm
    }
    // A custom start that doesn't parse is invalid (-1), quick picks are minutes, null quick = now (0).
    val startsIn = if (startCustom) minutes(startH, startM) ?: -1 else (startQuick ?: 0)
    val duration = if (durationCustom) minutes(forH, forM) else durationQuick
    val valid = startsIn >= 0 && (!durationCustom || (duration != null && duration > 0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock $childName's phone", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Starts", style = MaterialTheme.typography.labelLarge, color = KGColors.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KGChip("Now", !startCustom && startQuick == null, onClick = { startQuick = null; startCustom = false })
                    KGChip("In 30 min", !startCustom && startQuick == 30, onClick = { startQuick = 30; startCustom = false })
                    KGChip("In 1 hour", !startCustom && startQuick == 60, onClick = { startQuick = 60; startCustom = false })
                    KGChip("Custom…", startCustom, onClick = { startCustom = true })
                }
                if (startCustom) HoursMinutes("In", startH, startM, { startH = it }, { startM = it })

                Spacer(Modifier.height(4.dp))
                Text("Lasts", style = MaterialTheme.typography.labelLarge, color = KGColors.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KGChip("Until I unlock", !durationCustom && durationQuick == null, onClick = { durationQuick = null; durationCustom = false })
                    KGChip("1 hour", !durationCustom && durationQuick == 60, onClick = { durationQuick = 60; durationCustom = false })
                    KGChip("2 hours", !durationCustom && durationQuick == 120, onClick = { durationQuick = 120; durationCustom = false })
                    KGChip("Custom…", durationCustom, onClick = { durationCustom = true })
                }
                if (durationCustom) HoursMinutes("For", forH, forM, { forH = it }, { forM = it })

                Text(
                    if (startsIn > 0) "$childName gets a warning before the lock starts. Emergency calls always work."
                    else "Emergency calls always work while the phone is locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
            }
        },
        confirmButton = {
            KGPrimaryButton(
                text = "Lock phone",
                onClick = { if (valid) onConfirm(startsIn, duration) },
                enabled = valid
            )
        },
        dismissButton = { KGTextLink(text = "Cancel", onClick = onDismiss) }
    )
}

@Composable
private fun HoursMinutes(label: String, h: String, m: String, onH: (String) -> Unit, onM: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(36.dp))
        KGTextField(
            value = h,
            onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) onH(v) },
            label = "Hours",
            keyboardType = KeyboardType.Number,
            isError = (h.toIntOrNull() ?: -1) !in 0..23,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        KGTextField(
            value = m,
            onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) onM(v) },
            label = "Minutes",
            keyboardType = KeyboardType.Number,
            isError = (m.toIntOrNull() ?: -1) !in 0..59,
            modifier = Modifier.weight(1f)
        )
    }
}

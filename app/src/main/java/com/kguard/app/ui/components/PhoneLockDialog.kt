package com.kguard.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
 * [onConfirm] gets the delay before the lock starts (0 = now) and its length (null = until unlocked).
 */
@Composable
fun PhoneLockDialog(
    childName: String,
    onDismiss: () -> Unit,
    onConfirm: (startsInMinutes: Int, durationMinutes: Int?) -> Unit
) {
    var later by remember { mutableStateOf(false) }
    var startH by remember { mutableStateOf("0") }
    var startM by remember { mutableStateOf("30") }
    var timed by remember { mutableStateOf(false) }
    var forH by remember { mutableStateOf("1") }
    var forM by remember { mutableStateOf("0") }

    fun minutes(h: String, m: String): Int? {
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh * 60 + mm
    }
    val startsIn = if (later) minutes(startH, startM) else 0
    val duration = if (timed) minutes(forH, forM) else null
    val valid = startsIn != null && (!later || startsIn > 0) && (!timed || (duration != null && duration > 0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock $childName's phone", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Starts", style = MaterialTheme.typography.labelLarge, color = KGColors.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !later, onClick = { later = false }, label = { Text("Now") })
                    FilterChip(selected = later, onClick = { later = true }, label = { Text("Later") })
                }
                if (later) HoursMinutes("In", startH, startM, { startH = it }, { startM = it })

                Spacer(Modifier.height(4.dp))
                Text("Lasts", style = MaterialTheme.typography.labelLarge, color = KGColors.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !timed, onClick = { timed = false }, label = { Text("Until I unlock") })
                    FilterChip(selected = timed, onClick = { timed = true }, label = { Text("Set time") })
                }
                if (timed) HoursMinutes("For", forH, forM, { forH = it }, { forM = it })

                Text(
                    if (later) "$childName gets a warning before the lock starts. Emergency calls always work."
                    else "Emergency calls always work while the phone is locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KGColors.inkMuted
                )
            }
        },
        confirmButton = {
            KGPrimaryButton(
                text = "Lock phone",
                onClick = { if (valid) onConfirm(startsIn ?: 0, duration) },
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

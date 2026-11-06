package com.tele.k_guard.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tele.k_guard.ui.components.KGuardLogo
import com.tele.k_guard.ui.theme.KGColors
import com.tele.k_guard.ui.theme.KGShapes
import kotlinx.coroutines.delay

/*
 * The child's lock screen, drawn by the enforcement engine in an overlay window (see LockOverlay).
 *
 * Dark by design: a lock screen that may appear at night must not flood the room with light.
 * This is the app's one justified dark surface — pure black canvas, translucent-white cards,
 * white / white-60 text, and the single accent blue as signal. Translucent white alphas are
 * the sanctioned exception to the no-inline-hex rule on this surface.
 *
 * It runs outside any Activity, so nothing here may open a Dialog window: the PIN entry is inline.
 */

private val LockSurface = Color(0x14FFFFFF)
private val LockTextMuted = Color.White.copy(alpha = 0.6f)
private val LockBorder = Color.White.copy(alpha = 0.25f)

private const val MAX_FAILED_ATTEMPTS = 5
private const val LOCKOUT_MILLIS = 30_000L

enum class LockScreenKind { REMOTE, BEDTIME, LIMIT, APP_BLOCK, WEB_OFF }

@Composable
fun LockScreenContent(
    kind: LockScreenKind,
    heading: String,
    message: String,
    footnote: String,
    onEmergencyCall: () -> Unit,
    onDismissAppBlock: () -> Unit,
    /** WEB_OFF only: restarts web protection (or opens Android's one-time VPN prompt). */
    onTurnWebBackOn: () -> Unit = {},
    /** Checks the parent PIN; calls back with (success, error message). */
    onParentPinUnlock: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var showPin by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockoutUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lockedOut = now < lockoutUntil
    val secondsLeft = ((lockoutUntil - now + 999) / 1000).toInt().coerceAtLeast(0)

    LaunchedEffect(lockoutUntil) {
        while (lockoutUntil > now) { delay(1_000); now = System.currentTimeMillis() }
    }

    val icon: ImageVector = when (kind) {
        LockScreenKind.BEDTIME -> Icons.Default.Bedtime
        LockScreenKind.LIMIT -> Icons.Default.HourglassBottom
        LockScreenKind.APP_BLOCK -> Icons.Default.Block
        LockScreenKind.WEB_OFF -> Icons.Default.Public
        LockScreenKind.REMOTE -> Icons.Default.Lock
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KGuardLogo(modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("K-Guard Child Protection", style = MaterialTheme.typography.labelLarge, color = LockTextMuted)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(110.dp).clip(KGShapes.circle).background(LockSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = KGColors.accent, modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(heading, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Card(colors = CardDefaults.cardColors(containerColor = LockSurface), shape = KGShapes.card) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (kind == LockScreenKind.REMOTE) "NOTE FROM PARENT" else "WHY",
                        style = MaterialTheme.typography.labelSmall,
                        color = KGColors.accent,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(footnote, style = MaterialTheme.typography.bodyMedium, color = LockTextMuted, textAlign = TextAlign.Center)
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (kind == LockScreenKind.APP_BLOCK) {
                Button(
                    onClick = onDismissAppBlock,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = KGShapes.pill
                ) { Text("OK") }
            } else if (kind == LockScreenKind.WEB_OFF && !showPin) {
                Button(
                    onClick = onTurnWebBackOn,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = KGShapes.pill
                ) { Text("Turn web protection back on") }
                TextButton(onClick = { showPin = true }) { Text("Parent PIN Unlock", color = LockTextMuted) }
            } else if (showPin) {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
                    label = { Text("Parent PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = pinError != null,
                    enabled = !lockedOut,
                    singleLine = true,
                    shape = KGShapes.input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = KGColors.accent,
                        unfocusedBorderColor = LockBorder,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = LockTextMuted,
                        cursorColor = KGColors.accent
                    ),
                    supportingText = {
                        when {
                            lockedOut -> Text("Too many incorrect attempts. Try again in ${secondsLeft}s.")
                            pinError != null -> Text(pinError!!)
                            else -> Text("Unlocks this phone for 30 minutes.", color = LockTextMuted)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onParentPinUnlock(pinInput) { ok, msg ->
                            if (ok) {
                                showPin = false; pinInput = ""; pinError = null; failedAttempts = 0; lockoutUntil = 0L
                            } else {
                                pinError = msg
                                pinInput = ""
                                if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                                    lockoutUntil = System.currentTimeMillis() + LOCKOUT_MILLIS
                                    now = System.currentTimeMillis()
                                    failedAttempts = 0
                                }
                            }
                        }
                    },
                    enabled = !lockedOut && pinInput.length >= 4,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = KGShapes.pill
                ) { Text("Unlock") }
                TextButton(onClick = { showPin = false; pinInput = ""; pinError = null }) {
                    Text("Cancel", color = LockTextMuted)
                }
            } else {
                Button(
                    onClick = { showPin = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = KGShapes.pill
                ) {
                    Icon(Icons.Default.Password, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Parent PIN Unlock")
                }
            }

            if (kind != LockScreenKind.APP_BLOCK) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onEmergencyCall,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = KGShapes.pill,
                    border = BorderStroke(1.dp, LockBorder),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = KGColors.danger)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Emergency Call")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

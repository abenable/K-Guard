package com.kguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGStatusTag
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme

@Composable
fun ChildPinSetupScreen(
    onPinSaved: (String) -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val isValid = pin.length >= 4

    // Strength signal: weak = too short or guessable (repeated/sequential), strong = survives the checks.
    val weakReason = weakPinReason(pin)
    val strength = when {
        pin.isEmpty() -> null
        pin.length < 4 || weakReason != null -> "Weak PIN" to KGColors.warning
        else -> "Strong PIN" to KGColors.success
    }

    Scaffold(
        topBar = { KGuardTopAppBar("Uninstall Protection PIN", canNavigateBack = false) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, KGShapes.circle)
                    .padding(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Set Security PIN",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Enter a 4 to 6 digit PIN. This PIN will be required to uninstall K-Guard or change protection settings on this child device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            KGTextField(
                value = pin,
                onValueChange = { if (it.length <= 6) pin = it.filter { c -> c.isDigit() } },
                label = "Enter Security PIN (4-6 digits)",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.NumberPassword
            )
            strength?.let { (label, color) ->
                Box(Modifier.fillMaxWidth()) {
                    KGStatusTag(text = label, color = color, modifier = Modifier.padding(top = 8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            KGTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 6) confirmPin = it.filter { c -> c.isDigit() } },
                label = "Confirm Security PIN",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.NumberPassword
            )

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = KGColors.danger, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))

            KGPrimaryButton(
                text = "Save PIN & Activate Protection",
                onClick = {
                    val weak = weakPinReason(pin)
                    when {
                        pin.length < 4 -> error = "PIN must be at least 4 digits."
                        pin != confirmPin -> error = "PINs do not match."
                        weak != null -> error = weak
                        else -> onPinSaved(pin)
                    }
                },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Rejects guessable PINs: repeated digits and ascending/descending runs. */
private fun weakPinReason(pin: String): String? {
    if (pin.length < 4 || pin.length > 6) return null
    if (pin.all { it == pin.first() }) return "PIN cannot use the same digit throughout (e.g. 1111)."
    val digits = pin.map { it.code - '0'.code }
    if (digits.zipWithNext().all { (a, b) -> b - a == 1 }) return "PIN cannot use sequential digits (e.g. 1234)."
    if (digits.zipWithNext().all { (a, b) -> a - b == 1 }) return "PIN cannot use descending sequential digits (e.g. 4321)."
    return null
}

@Preview(showBackground = true)
@Composable
fun ChildPinSetupPreview() {
    KGuardTheme {
        ChildPinSetupScreen(onPinSaved = {})
    }
}

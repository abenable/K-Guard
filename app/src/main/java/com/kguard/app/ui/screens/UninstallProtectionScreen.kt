package com.kguard.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kguard.app.protection.DeviceProtection
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGSectionHeader
import com.kguard.app.ui.components.KGuardTopAppBar
import com.kguard.app.ui.theme.KGColors

/**
 * Final step of child-device setup: turn on device administrator, which is what stops Android
 * from ever uninstalling K-Guard while protection is on, and what makes remote lock work.
 * Skippable — the child dashboard keeps a warning card until it is enabled.
 */
@Composable
fun UninstallProtectionScreen(
    onProtected: () -> Unit = {},
    onSkipped: () -> Unit = {}
) {
    val context = LocalContext.current
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (DeviceProtection.isAdminActive(context)) onProtected()
    }
    // The admin can also be granted while the system screen is open; re-check on resume.
    LaunchedEffect(Unit) { if (DeviceProtection.isAdminActive(context)) onProtected() }

    Column(Modifier.fillMaxSize()) {
        KGuardTopAppBar(
            title = "Uninstall protection",
            canNavigateBack = false
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = KGColors.accent,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("Keep K-Guard on this phone", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Give K-Guard device administrator rights so the app cannot simply be removed — by accident or on purpose.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(20.dp))

            KGCard(Modifier.fillMaxWidth()) {
                ProtectionRow(Icons.Default.Lock, "Uninstall blocked", "The app can no longer be removed while protection is on.")
                Spacer(Modifier.height(12.dp))
                ProtectionRow(Icons.Default.Shield, "Remote lock works", "Parents can switch this phone off or lock it instantly.")
            }

            KGSectionHeader(
                "What to expect",
                hint = "Android will show a system screen asking you to activate K-Guard as a device administrator. Accept it there."
            )

            Spacer(Modifier.height(8.dp))

            KGPrimaryButton(
                text = "Turn on protection",
                onClick = { adminLauncher.launch(DeviceProtection.activationIntent(context)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            KGSecondaryButton(
                text = "Not now",
                onClick = onSkipped,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProtectionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = KGColors.success,
            modifier = Modifier.size(24.dp)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

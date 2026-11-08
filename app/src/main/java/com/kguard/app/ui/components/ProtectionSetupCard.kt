package com.kguard.app.ui.components

import com.kguard.app.vpn.KGuardVpnService
import com.kguard.app.vpn.ChildWebProtectionSync
import android.provider.Settings
import android.content.Intent
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kguard.app.protection.DeviceProtection
import com.kguard.app.service.KGuardCompanionService
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.util.OsUsageTracker

/** One permission the child's phone needs, with the reason shown to the person setting it up. */
private data class SetupStep(val title: String, val why: String, val done: Boolean, val open: () -> Unit)

/**
 * Child's phone: what Android requires the person holding the phone to allow before screen time and
 * locks can work (no app can grant these remotely). Re-checks on return from Settings and asks the
 * companion service to report, so the parent sees the change at once. Hidden once every step is done.
 */
@Composable
fun ProtectionSetupCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val recheck = {
        refresh++
        KGuardCompanionService.requestSync(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recheck() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    // The device-admin screen must be started from this activity; it will not open as a new task.
    val deviceAdmin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck() }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }

    val steps = remember(refresh) {
        setupSteps(
            context,
            askNotifications = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
            askDeviceAdmin = { runCatching { deviceAdmin.launch(DeviceProtection.activationIntent(context)) } },
            askLocation = { location.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        )
    }
    val missing = steps.filterNot { it.done }
    if (missing.isEmpty()) return

    KGCard(modifier = modifier.fillMaxWidth()) {
        Text("Finish setting up protection", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            "${steps.size - missing.size} of ${steps.size} done. Android needs someone holding this phone to allow these. Your parent can see what's left.",
            style = MaterialTheme.typography.bodySmall,
            color = KGColors.inkMuted
        )
        missing.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(step.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(step.why, style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted)
                }
                KGTextLink(text = "Allow", onClick = step.open)
            }
        }
    }
}

private fun setupSteps(context: Context, askNotifications: () -> Unit, askDeviceAdmin: () -> Unit, askLocation: () -> Unit): List<SetupStep> = buildList {
    add(SetupStep("Usage access", "Measures screen time and sees which app is open.", OsUsageTracker.hasUsageStatsPermission(context)) {
        OsUsageTracker.openUsageAccessSettings(context)
    })
    add(SetupStep("Display over other apps", "Shows the lock screen at bedtime, at the limit, or when your parent locks the phone.", OsUsageTracker.canDrawOverlays(context)) {
        OsUsageTracker.openOverlaySettings(context)
    })
    add(SetupStep("Device admin", "Switches the screen off when a lock starts, and stops K-Guard being removed unnoticed.", DeviceProtection.isAdminActive(context), askDeviceAdmin))
    add(SetupStep("Wi-Fi name (Location)", "Lets your parent see which Wi-Fi network this phone is connected to.", locationGranted(context), askLocation))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        add(SetupStep("Notifications", "Warns you 10 minutes before bedtime or the end of your screen time.", granted, askNotifications))
    }
    add(SetupStep("Unrestricted battery", "Stops Android pausing protection to save battery.", OsUsageTracker.isIgnoringBatteryOptimizations(context)) {
        OsUsageTracker.openBatterySettings(context)
    })
    // Only when the parent has web protection on and the running filter can tell (Android 10+).
    if (ChildWebProtectionSync.wantsOn) KGuardVpnService.isAlwaysOn()?.let { on ->
        add(
            SetupStep(
                "Always-on web protection",
                "In VPN settings, tap the gear next to K-Guard and turn on Always-on VPN. Leave \"Block connections without VPN\" off.",
                on
            ) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        )
    }
}

private fun locationGranted(context: Context) = ContextCompat.checkSelfPermission(
    context, Manifest.permission.ACCESS_FINE_LOCATION
) == PackageManager.PERMISSION_GRANTED

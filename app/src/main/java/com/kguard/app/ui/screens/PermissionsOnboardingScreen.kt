package com.kguard.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kguard.app.protection.DeviceProtection
import com.kguard.app.service.KGuardCompanionService
import com.kguard.app.ui.components.KGCard
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGSecondaryButton
import com.kguard.app.ui.components.KGShapes
import com.kguard.app.ui.theme.KGColors
import com.kguard.app.util.OsUsageTracker
import com.kguard.app.vpn.ChildWebProtectionSync
import com.kguard.app.vpn.KGuardVpnService

private const val PREFS = "kguard_child_onboarding"
private const val KEY_SEEN = "permissions_onboarding_seen"

/** Shown once per install; after that the dashboard's setup card takes over if something is still missing. */
fun markOnboardingSeen(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
}

fun onboardingSeen(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

/** True when every permission the enforcement engine needs has been granted on this phone. */
fun childPermissionsComplete(context: Context): Boolean = allPermissions(context).all { it.done }

private data class PermissionStep(val title: String, val why: String, val done: Boolean, val open: () -> Unit)

private fun allPermissions(context: Context): List<PermissionStep> = buildList {
    add(PermissionStep("Usage access", "Lets K-Guard measure screen time and see which app is open, so limits and app blocking can work.", OsUsageTracker.hasUsageStatsPermission(context)) {
        OsUsageTracker.openUsageAccessSettings(context)
    })
    add(PermissionStep("Display over other apps", "Shows the lock screen at bedtime, when time is up, or when your parent locks the phone.", OsUsageTracker.canDrawOverlays(context)) {
        OsUsageTracker.openOverlaySettings(context)
    })
    add(PermissionStep("Device admin", "Switches the screen off when a lock starts, and stops K-Guard being removed unnoticed.", DeviceProtection.isAdminActive(context)) {
        runCatching { context.startActivity(DeviceProtection.activationIntent(context)) }
    })
    add(PermissionStep("Wi-Fi name (Location)", "Lets your parent see which Wi-Fi network this phone is on.", locationGranted(context)) {
        // Opened by the runtime permission launcher in the composable; this fallback is the settings page.
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }
    })
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionStep("Notifications", "Warns you 10 minutes before bedtime or before your screen time runs out.", notificationGranted(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        })
    }
    add(PermissionStep("Unrestricted battery", "Stops Android pausing protection to save battery.", OsUsageTracker.isIgnoringBatteryOptimizations(context)) {
        OsUsageTracker.openBatterySettings(context)
    })
}

private fun locationGranted(context: Context) = ContextCompat.checkSelfPermission(
    context, Manifest.permission.ACCESS_FINE_LOCATION
) == PackageManager.PERMISSION_GRANTED

private fun notificationGranted(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * The first-run permission walk-through on a freshly paired child's phone. Everything the enforcement
 * engine needs is asked for here, in one guided pass, instead of being discovered missing later on the
 * dashboard. Re-checks when returning from the system screens; completes on its own once all steps are
 * done. A device-admin step that needs the web-protection VPN consent (when the parent already turned it
 * on) is offered here too, so nobody has to come back to this phone twice.
 */
@Composable
fun PermissionsOnboardingScreen(onAllDone: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val recheck = { refresh++; KGuardCompanionService.requestSync(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recheck() }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    val deviceAdminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck() }
    val vpnConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck() }

    val steps = remember(refresh) {
        allPermissions(context).toMutableList().apply {
            // The one-time VPN consent, only when the parent already switched web protection on.
            if (com.kguard.app.vpn.ChildWebProtectionSync.wantsOn && KGuardVpnService.isAlwaysOn() == null) {
                android.net.VpnService.prepare(context)?.let {
                    add(PermissionStep("Allow web protection", "Your parent turned on web protection; Android needs one OK on this phone.", false) {
                        runCatching { vpnConsentLauncher.launch(Intent(context, VpnConsentActivity::class.java)) }
                    })
                }
            }        }
    }
    val missing = steps.filterNot { it.done }
    val done = steps.size - missing.size

    LaunchedEffect(missing.isEmpty()) { if (missing.isEmpty() && steps.isNotEmpty()) { markOnboardingSeen(context); onAllDone() } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Text("Finish setting up K-Guard", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android requires the person holding this phone to allow each of these once. " +
                    "Your parent can see what is still missing.",
                style = MaterialTheme.typography.bodyMedium,
                color = KGColors.inkMuted
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { if (steps.isEmpty()) 1f else done.toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text("$done of ${steps.size} done", style = MaterialTheme.typography.labelMedium, color = KGColors.inkMuted)
            Spacer(Modifier.height(16.dp))

            missing.forEach { step ->
                KGCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(step.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(step.why, style = MaterialTheme.typography.bodySmall, color = KGColors.inkMuted)
                        }
                        Spacer(Modifier.height(8.dp))
                        when (step.title) {
                            "Notifications" -> KGSecondaryButton(text = "Allow", onClick = {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            })
                            "Wi-Fi name (Location)" -> KGSecondaryButton(text = "Allow", onClick = {
                                locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            })
                            "Device admin" -> KGSecondaryButton(text = "Allow", onClick = {
                                runCatching { deviceAdminLauncher.launch(DeviceProtection.activationIntent(context)) }
                            })
                            "Allow web protection" -> KGSecondaryButton(text = "OK", onClick = step.open)
                            else -> KGSecondaryButton(text = "Allow", onClick = step.open)
                        }
                    }
                }
            }

            if (missing.isEmpty()) {
                KGPrimaryButton(text = "You're all set", onClick = onAllDone, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(8.dp))
                KGSecondaryButton(text = "Skip for now", onClick = {
                    markOnboardingSeen(context)
                    onSkip()
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun Modifier.background() = this

package com.kguard.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kguard.app.protection.DeviceProtection

/**
 * Restarts web protection after a reboot when the parent left it on, so filtering does not silently
 * disappear overnight. Also kicks off the tamper watchdog so uninstall protection and services are
 * re-asserted periodically. Requires RECEIVE_BOOT_COMPLETED and a manifest receiver entry for
 * ACTION_BOOT_COMPLETED (exported="false" is correct: the system delivers protected broadcasts to
 * non-exported manifest receivers, and no third-party app may trigger this one).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTART_ACTIONS) return
        if (VpnConfigStore(context).load().enabled) {
            ContextCompat.startForegroundService(context, Intent(context, KGuardVpnService::class.java))
        }
        DeviceProtection.assertProtections(context) // also restarts the child's enforcement engine
        DeviceProtection.scheduleWatchdog(context)
    }

    private companion object {
        val RESTART_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}

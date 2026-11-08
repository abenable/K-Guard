package com.kguard.app.protection

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kguard.app.data.repository.UserPreferencesRepository
import com.kguard.app.receiver.KGuardDeviceAdminReceiver
import com.kguard.app.service.KGuardMessagingService
import com.kguard.app.service.KGuardCompanionService
import com.kguard.app.vpn.KGuardVpnService
import com.kguard.app.vpn.VpnConfigStore
import com.kguard.app.vpn.VpnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The child-device protection core: device-admin based uninstall protection plus a watchdog
 * that re-asserts K-Guard's services if something (or someone) stopped them.
 *
 * How the pieces protect the app:
 * - While [KGuardDeviceAdminReceiver] is an ACTIVE administrator, Android refuses to uninstall
 *   the app ("Uninstall blocked: this app is a device administrator") — that is the removal
 *   protection, and it is enforced by the OS, not by us.
 * - The admin must be deactivated in system settings before uninstalling; we make that dialog
 *   painful (see [KGuardDeviceAdminReceiver.onDisableRequested]) and if it is deactivated anyway,
 *   [ProtectionReceiver]'s watchdog keeps nagging to re-enable it.
 * - The watchdog also restarts the VPN service when it is supposed to be on but is not running.
 *
 * This is the honest limit for a non-device-owner app: the child (or anyone holding the device)
 * can still walk to system settings and deactivate the admin. A device-owner/MDM profile would
 * make that impossible, but provisioning requires dedicated enrollment, not an app install.
 */
object DeviceProtection {

    private const val WATCHDOG_ACTION = "com.kguard.app.protection.WATCHDOG"
    private const val WATCHDOG_REQUEST_CODE = 4712
    private const val WATCHDOG_INTERVAL_MS = 15L * 60 * 1000
    private const val NAG_PREFS = "protection_nag"
    private const val NAG_KEY_LAST = "last_admin_nag"
    private const val NAG_KEY_ENGINE = "last_engine_nag"
    private const val NAG_INTERVAL_MS = 60L * 60 * 1000

    fun adminComponent(context: Context) =
        ComponentName(context, KGuardDeviceAdminReceiver::class.java)

    /** True while Android treats K-Guard as an active device administrator (uninstall blocked). */
    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            ?: return false
        return dpm.isAdminActive(adminComponent(context))
    }

    /** The system activation screen for device admin ("Turn on uninstall protection"). */
    fun activationIntent(context: Context): Intent =
        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            .putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "K-Guard needs device administrator rights to protect this child's phone: " +
                    "it enables remote lock and stops the app from being uninstalled while protection is on."
            )

    /** Schedules the periodic re-assert cycle. Cheap and idempotent; call from app start and boot. */
    fun scheduleWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            Intent(WATCHDOG_ACTION).setClass(context, ProtectionReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + WATCHDOG_INTERVAL_MS, pi)
    }

    /**
     * Re-asserts everything on a child device: VPN back up if it should be running, and a
     * rate-limited notification if uninstall protection was switched off. Safe to call often.
     */
    fun assertProtections(context: Context) {
        val isChild = runCatching {
            runBlocking { UserPreferencesRepository(context).isChildDeviceFlow.first() }
        }.getOrDefault(false)
        if (!isChild) return

        // Screen time engine: locks, schedules and limits stop if this service is killed. If Android won't let
        // the alarm restart it (Android 12+ without unrestricted battery), ask for a tap: opening K-Guard
        // starts it, and a user action is always allowed to.
        if (!KGuardCompanionService.isRunning && !KGuardCompanionService.startCompanionService(context)) {
            rateLimited(context, NAG_KEY_ENGINE) {
                KGuardMessagingService.showAlert(
                    context,
                    "K-Guard protection is paused",
                    "Tap to turn screen time protection back on. Allowing K-Guard unrestricted battery use keeps it running."
                )
            }
        }

        // VPN: if the parent left protection on but the service is not running, bring it back.
        if (VpnConfigStore(context).load().enabled && !VpnState.running.value) {
            KGuardVpnService.start(context)
        }

        // Uninstall protection: nag (at most hourly) until the admin is active again.
        if (!isAdminActive(context)) {
            rateLimited(context, NAG_KEY_LAST) {
                KGuardMessagingService.showAlert(
                    context,
                    "K-Guard protection is weakened",
                    "Uninstall protection was turned off. Open K-Guard and turn device administrator back on so the app cannot be removed."
                )
            }
        }
    }

    /** Runs [show] at most once per [NAG_INTERVAL_MS] for [key]. */
    private inline fun rateLimited(context: Context, key: String, show: () -> Unit) {
        val prefs = context.getSharedPreferences(NAG_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(key, 0L) > NAG_INTERVAL_MS) {
            prefs.edit().putLong(key, now).apply()
            show()
        }
    }
}

/**
 * Alarm-driven watchdog: runs every [DeviceProtection] interval while the device is alive,
 * re-scheduling itself each time. Manifest-registered (not exported) so it survives process death.
 */
class ProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeviceProtection.assertProtections(context)
        DeviceProtection.scheduleWatchdog(context)
    }
}

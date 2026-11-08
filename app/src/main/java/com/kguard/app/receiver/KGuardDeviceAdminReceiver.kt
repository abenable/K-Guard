package com.kguard.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.app.admin.DevicePolicyManager
import com.kguard.app.protection.DeviceProtection
import com.kguard.app.service.KGuardCompanionService
import com.kguard.app.service.KGuardMessagingService

/**
 * Device Administrator receiver. While active it gives K-Guard two protections:
 * 1. Remote lock (DevicePolicyManager.lockNow) for the "Switch off phone" parent action.
 * 2. Uninstall protection — Android refuses to remove the app while it is an active
 *    administrator, so this receiver being enabled IS the anti-removal mechanism.
 */
class KGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        KGuardMessagingService.showAlert(
            context,
            "Uninstall protection is on",
            "K-Guard can no longer be removed from this device, and remote lock is active."
        )
        KGuardCompanionService.requestSync(context)
    }

    /**
     * Shown in the system's "deactivate administrator?" dialog. The child sees this before
     * they can weaken protection — make the consequences explicit, in child-appropriate words.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        super.onDisableRequested(context, intent)
        return (
            "Turning this off will let the K-Guard app be uninstalled and will stop " +
                "remote locking. Your parent gets told when protection stops working. " +
                "Really switch it off?"
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // The app can now be uninstalled; tell the child plainly. The watchdog nags hourly
        // until the administrator is re-enabled (see DeviceProtection.assertProtections).
        KGuardMessagingService.showAlert(
            context,
            "K-Guard protection was turned off",
            "This app can now be removed and remote lock no longer works. Open K-Guard to turn protection back on."
        )
        // Report straight away; the server tells the parent (see onDeviceProtectionChanged).
        KGuardCompanionService.requestSync(context)
    }

    companion object {
        fun isAdminActive(context: Context): Boolean = DeviceProtection.isAdminActive(context)

        /** Switches the screen off via DevicePolicyManager.lockNow(); false when admin is not active. */
        fun lockScreenNow(context: Context): Boolean {
            if (!isAdminActive(context)) return false
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
            return runCatching { dpm.lockNow() }.isSuccess
        }

        /** Start from an Activity: Android refuses ADD_DEVICE_ADMIN as a new task. */
        fun createAdminRequestIntent(context: Context): Intent = DeviceProtection.activationIntent(context)
    }
}

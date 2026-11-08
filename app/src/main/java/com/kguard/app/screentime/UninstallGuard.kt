package com.kguard.app.screentime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.admin.DevicePolicyManager
import android.util.Log
import com.kguard.app.receiver.KGuardDeviceAdminReceiver
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Lets K-Guard be removed from a child's phone only after the parent's PIN is entered in the K-Guard app.
 *
 * While K-Guard's device admin is on, Android sends every uninstall (and every attempt to switch protection
 * off) through its "deactivate device admin" screen. No app may draw over that screen, so the companion
 * service locks the phone when it appears and puts this PIN prompt in front (unless the PIN was entered here
 * within the last few minutes). With the right PIN, K-Guard switches its device admin off itself.
 */
object UninstallGuard {
    private const val PREFS = "uninstall_guard"
    private const val KEY_UNTIL = "allowed_until"
    private const val KEY_FAILS = "failed_attempts"
    private const val KEY_LOCKED_UNTIL = "locked_until"
    const val WINDOW_MS = 2 * 60_000L
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 60_000L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAllowed(context: Context): Boolean = System.currentTimeMillis() < prefs(context).getLong(KEY_UNTIL, 0L)

    /** Milliseconds until another PIN attempt is allowed, or 0. */
    fun lockoutRemaining(context: Context): Long =
        (prefs(context).getLong(KEY_LOCKED_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun recordSuccess(context: Context) {
        prefs(context).edit()
            .putLong(KEY_UNTIL, System.currentTimeMillis() + WINDOW_MS)
            .putInt(KEY_FAILS, 0)
            .apply()
    }

    /** Returns the attempts left before a lockout (0 means the lockout just started). */
    fun recordFailure(context: Context): Int {
        val p = prefs(context)
        val fails = p.getInt(KEY_FAILS, 0) + 1
        return if (fails >= MAX_ATTEMPTS) {
            p.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_MS).apply()
            0
        } else {
            p.edit().putInt(KEY_FAILS, fails).apply()
            MAX_ATTEMPTS - fails
        }
    }

    /** Set by the companion service when it catches an attempt; the child dashboard then asks for the PIN. */
    val promptRequested = MutableStateFlow(false)

    /**
     * After the PIN: K-Guard switches off its own device admin (the parent is told, see
     * KGuardDeviceAdminReceiver.onDisabled) and opens Android's normal "uninstall this app?" prompt.
     */
    suspend fun releaseAndUninstall(context: Context) {
        if (KGuardDeviceAdminReceiver.isAdminActive(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            runCatching { dpm.removeActiveAdmin(com.kguard.app.protection.DeviceProtection.adminComponent(context)) }
                .onFailure { Log.e("KGuardUninstall", "Could not switch off device admin", it) }
            // Android finishes removing the admin asynchronously; wait so the uninstall is not refused.
            var waited = 0
            while (KGuardDeviceAdminReceiver.isAdminActive(context) && waited < 3_000) {
                kotlinx.coroutines.delay(100); waited += 100
            }
        }
        startUninstall(context)
    }

    /**
     * Opens K-Guard's App info page, where Uninstall now works directly. (Opening Android's uninstall prompt
     * from the app would need REQUEST_DELETE_PACKAGES, which Google Play restricts.)
     */
    fun startUninstall(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e("KGuardUninstall", "Could not open App info", it) }
    }
}

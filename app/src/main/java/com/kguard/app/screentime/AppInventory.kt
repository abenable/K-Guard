package com.kguard.app.screentime

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Keeps the parent informed about apps on the child's phone.
 *
 * - First run after pairing: every app already on the phone is recorded as allowed (`syncInstalledApps`),
 *   so the parent sees the full list and can block any of them, without being flooded with requests.
 * - A new install is sent for approval (`requestAppApproval`). The parent is notified and the app stays
 *   blocked on the phone until approved.
 * - An uninstall is reported (`reportAppRemoved`) and the parent is notified.
 *
 * Changes are found both from Android's install broadcasts and by comparing the installed list with the last
 * one seen, so installs made while K-Guard was not running are caught on its next start. Anything that cannot
 * be sent (offline) is queued and retried.
 */
class AppInventory(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("app_inventory", Context.MODE_PRIVATE)
    private val functions = FirebaseFunctions.getInstance()
    private val reader = UsageReader(app)
    private val lock = Mutex()

    data class InstalledApp(val packageName: String, val appName: String, val category: Int)

    /** Apps the child can open: anything with a launcher icon, except K-Guard. */
    fun installedApps(): List<InstalledApp> {
        val pm = app.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { pm.queryIntentActivities(main, 0) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != app.packageName }
            .map { InstalledApp(it, reader.appLabel(it), reader.appCategory(it)) }
    }

    /** Compares the installed list with the last one seen and reports the difference. */
    suspend fun reconcile() = lock.withLock {
        val now = installedApps()
        val current = now.map { it.packageName }.toSet()
        val known = prefs.getStringSet(KEY_KNOWN, null)

        if (known == null) {
            // First run on this phone: record what is already here as allowed.
            if (runCatching { sendBaseline(now) }.isSuccess) {
                prefs.edit().putStringSet(KEY_KNOWN, current).apply()
                Log.i(TAG, "Recorded ${now.size} existing apps for the parent")
            }
            return@withLock
        }

        val added = current - known
        val removed = known - current
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            val queueAdd = prefs.getStringSet(KEY_QUEUE_ADD, emptySet())!! + added - removed
            val queueRemove = prefs.getStringSet(KEY_QUEUE_REMOVE, emptySet())!! + removed - added
            prefs.edit()
                .putStringSet(KEY_KNOWN, current)
                .putStringSet(KEY_QUEUE_ADD, queueAdd)
                .putStringSet(KEY_QUEUE_REMOVE, queueRemove)
                .apply()
        }
        flushQueues(now.associateBy { it.packageName })
    }

    /** Called from the install/uninstall broadcast so the parent hears within seconds. */
    suspend fun onPackageChanged() = reconcile()

    private suspend fun flushQueues(installed: Map<String, InstalledApp>) {
        val adds = prefs.getStringSet(KEY_QUEUE_ADD, emptySet())!!
        val removes = prefs.getStringSet(KEY_QUEUE_REMOVE, emptySet())!!
        val removedNames = prefs.getStringSet(KEY_NAMES, emptySet())!!.associate { it.substringBefore('|') to it.substringAfter('|') }

        val doneAdd = mutableSetOf<String>()
        for (pkg in adds) {
            val info = installed[pkg] ?: run { doneAdd += pkg; null } ?: continue // installed then removed again
            val ok = runCatching {
                functions.getHttpsCallable("requestAppApproval").call(
                    mapOf(
                        "appName" to info.appName,
                        "packageName" to info.packageName,
                        "category" to UsageReader.categoryLabel(info.category),
                        "minAgeRating" to suggestedMinAge(info.category)
                    )
                ).await()
            }.onFailure { Log.w(TAG, "Install report for $pkg failed; will retry", it) }.isSuccess
            if (ok) doneAdd += pkg
        }

        val doneRemove = mutableSetOf<String>()
        for (pkg in removes) {
            val ok = runCatching {
                functions.getHttpsCallable("reportAppRemoved").call(
                    mapOf("packageName" to pkg, "appName" to (removedNames[pkg] ?: pkg))
                ).await()
            }.onFailure { Log.w(TAG, "Uninstall report for $pkg failed; will retry", it) }.isSuccess
            if (ok) doneRemove += pkg
        }

        // Remember labels while apps are installed: after an uninstall Android can no longer tell us the name.
        val names = installed.values.map { "${it.packageName}|${it.appName}" }.toSet() +
            removedNames.filterKeys { it in removes - doneRemove }.map { "${it.key}|${it.value}" }
        prefs.edit()
            .putStringSet(KEY_QUEUE_ADD, adds - doneAdd)
            .putStringSet(KEY_QUEUE_REMOVE, removes - doneRemove)
            .putStringSet(KEY_NAMES, names)
            .apply()
    }

    private suspend fun sendBaseline(apps: List<InstalledApp>) {
        for (chunk in apps.chunked(100)) {
            functions.getHttpsCallable("syncInstalledApps").call(
                mapOf("apps" to chunk.map {
                    mapOf("packageName" to it.packageName, "appName" to it.appName, "category" to UsageReader.categoryLabel(it.category))
                })
            ).await()
        }
        prefs.edit().putStringSet(KEY_NAMES, apps.map { "${it.packageName}|${it.appName}" }.toSet()).apply()
    }

    /** A starting point for the parent; they can change the approval and limits for any app. */
    private fun suggestedMinAge(category: Int): Int = when (category) {
        ApplicationInfo.CATEGORY_SOCIAL -> 13
        ApplicationInfo.CATEGORY_GAME -> 10
        ApplicationInfo.CATEGORY_VIDEO -> 7
        else -> 3
    }

    /** Forget everything (used when the phone is unpaired). */
    fun reset() = prefs.edit().clear().apply()

    private companion object {
        const val TAG = "KGuardApps"
        const val KEY_KNOWN = "known"
        const val KEY_QUEUE_ADD = "queue_add"
        const val KEY_QUEUE_REMOVE = "queue_remove"
        const val KEY_NAMES = "names"
    }
}

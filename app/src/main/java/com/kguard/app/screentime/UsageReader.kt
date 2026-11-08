package com.kguard.app.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.kguard.app.util.OsUsageTracker
import java.util.Calendar

/** Reads today's usage from Android. Needs the "Usage access" permission on the child's phone. */
class UsageReader(private val context: Context) {
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val pm: PackageManager = context.packageManager

    fun hasAccess(): Boolean = OsUsageTracker.hasUsageStatsPermission(context)

    fun isScreenOn(): Boolean = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** Home-screen apps plus K-Guard: time spent in them never counts. */
    val excluded: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = runCatching { pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        (launchers + context.packageName + "com.android.systemui").toSet()
    }

    /** Full calculation for today. Null without usage access. */
    fun today(): UsageMath.Result? {
        val m = usm ?: return null
        if (!hasAccess()) return null
        val dayStart = startOfToday()
        val now = System.currentTimeMillis()
        // Look back a few hours so an app opened before midnight is known to be in the foreground.
        val events = read(m, dayStart - 3 * 3_600_000L, now)
        val cal = Calendar.getInstance()
        return UsageMath.accumulate(events, dayStart, now, excluded, { t -> cal.timeInMillis = t; cal.get(Calendar.HOUR_OF_DAY) }, isScreenOn())
    }

    /** Cheap check of what is on screen now, from events since [sinceMillis]; null if nothing changed. */
    /**
     * The activity class most recently brought to the front since [sinceMillis] (null when the screen went
     * off), or null if nothing changed. Used to spot Android's "deactivate device admin" screen, which is the
     * step every uninstall of K-Guard has to pass through while its device admin is on.
     */
    fun latestForegroundActivity(sinceMillis: Long): Pair<String?, String?>? {
        val m = usm ?: return null
        if (!hasAccess()) return null
        val events = runCatching { m.queryEvents(sinceMillis, System.currentTimeMillis()) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        var result: Pair<String?, String?>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageMath.ACTIVITY_RESUMED -> result = e.packageName to e.className
                UsageMath.SCREEN_NON_INTERACTIVE, UsageMath.KEYGUARD_SHOWN -> result = null to null
            }
        }
        return result
    }

    fun latestForegroundChange(sinceMillis: Long): Pair<String?, Long>? {
        val m = usm ?: return null
        if (!hasAccess()) return null
        val now = System.currentTimeMillis()
        var result: Pair<String?, Long>? = null
        for (e in read(m, sinceMillis, now)) {
            when (e.type) {
                UsageMath.ACTIVITY_RESUMED -> result = (e.packageName?.takeIf { it !in excluded }) to e.timeMillis
                UsageMath.SCREEN_NON_INTERACTIVE, UsageMath.KEYGUARD_SHOWN -> result = null to e.timeMillis
            }
        }
        return result
    }

    private fun read(m: UsageStatsManager, from: Long, to: Long): List<UsageMath.Event> {
        val out = ArrayList<UsageMath.Event>()
        val events = runCatching { m.queryEvents(from, to) }.getOrNull() ?: return out
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            out += UsageMath.Event(e.packageName, e.eventType, e.timeStamp)
        }
        return out
    }

    fun appLabel(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** [ApplicationInfo.category], or -1 when unknown. */
    fun appCategory(pkg: String): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching { pm.getApplicationInfo(pkg, 0).category }.getOrDefault(-1)
    } else -1

    companion object {
        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun categoryLabel(category: Int): String = when (category) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_VIDEO -> "Video"
            ApplicationInfo.CATEGORY_AUDIO -> "Music & Audio"
            ApplicationInfo.CATEGORY_NEWS -> "News"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            ApplicationInfo.CATEGORY_MAPS -> "Maps"
            ApplicationInfo.CATEGORY_IMAGE -> "Photos"
            else -> "General"
        }
    }
}

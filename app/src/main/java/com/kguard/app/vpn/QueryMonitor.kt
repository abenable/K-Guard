package com.kguard.app.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Watches every DNS lookup that passes through [KGuardVpnService] and flags the ones that match a category list.
 *
 * Privacy: in every build only per-day counts are kept (see [DayCounts]), and only the last [KEEP_DAYS] days.
 * Domain names are held in memory and logged only in debuggable builds, for the diagnostics view.
 *
 * ponytail: a burst of lookups is saved in one write, [FLUSH_MS] after the first, so a process kill can lose a few seconds of counts.
 */
object QueryMonitor {

    enum class Outcome { ALLOWED, BLOCKED, REWRITTEN, UNANSWERED }

    /** One lookup, kept in memory in debuggable builds only. [addedMs] is the time K-Guard added, excluding the upstream resolver. */
    class Entry(
        val time: Long,
        val domain: String,
        val type: String,
        val category: String?,
        val outcome: Outcome,
        val addedMs: Long
    )

    private const val FLUSH_MS = 10_000L
    private const val KEEP_DAYS = 7
    private const val RECENT_MAX = 200
    private const val LATENCY_MAX = 500
    private const val TAG = "KGuardDNS"

    private var prefs: SharedPreferences? = null
    // UTC-fixed day keys: a timezone change or travel must not distort or duplicate a day's counts.
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    } // only used inside synchronized methods
    private var dayKey = ""
    private var handler: Handler? = null
    private var flushPending = false
    private val latencies = ArrayDeque<Long>()

    private val _today = MutableStateFlow(DayCounts())
    val today: StateFlow<DayCounts> get() = _today

    private val _recent = MutableStateFlow<List<Entry>>(emptyList())
    val recent: StateFlow<List<Entry>> get() = _recent

    /** True in debuggable builds, where the recent-lookup log and latency figures are available. */
    @Volatile var debug = false
        private set

    /** Idempotent: safe to call from every entry point (service, UI card); the first call wins. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("dns_stats", Context.MODE_PRIVATE)
        handler = Handler(Looper.getMainLooper())
        debug = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        dayKey = currentKey()
        _today.value = DayCounts.decode(prefs?.getString(dayKey, null))
    }

    @Synchronized
    fun record(domain: String, type: String, verdict: Verdict, outcome: Outcome, addedMs: Long) {
        if (currentKey() != dayKey) rollOver()
        _today.value = _today.value.withQuery(outcome == Outcome.BLOCKED, verdict.category?.id)
        if (debug) {
            val e = Entry(System.currentTimeMillis(), domain, type, verdict.category?.id, outcome, addedMs)
            _recent.value = (listOf(e) + _recent.value).take(RECENT_MAX)
            if (outcome != Outcome.UNANSWERED) {
                latencies.addLast(addedMs)
                if (latencies.size > LATENCY_MAX) latencies.removeFirst()
            }
            Log.d(TAG, "$domain $type ${outcome.name} category=${e.category} +${addedMs}ms")
        }
        if (!flushPending) {
            flushPending = true
            handler?.postDelayed({ flush() }, FLUSH_MS)
        }
    }

    /** Median and 95th-percentile time K-Guard added, over the last lookups. Debug builds only. */
    @Synchronized
    fun latency(): Pair<Long, Long>? {
        if (!debug || latencies.isEmpty()) return null
        val sorted = latencies.sorted()
        return percentile(sorted, 50) to percentile(sorted, 95)
    }

    @Synchronized
    fun flush() {
        val p = prefs ?: return
        flushPending = false
        // Keep exactly KEEP_DAYS days: today plus the KEEP_DAYS-1 days before it.
        val cutoff = keyDaysAgo(KEEP_DAYS - 1)
        val edit = p.edit().putString(dayKey, _today.value.encode())
        p.all.keys.filter { it.length == 8 && it.all(Char::isDigit) && it < cutoff }.forEach { edit.remove(it) }
        edit.apply()
    }

    private fun rollOver() {
        flush()
        dayKey = currentKey()
        _today.value = DayCounts.decode(prefs?.getString(dayKey, null))
    }

    private fun currentKey(): String = dayFormat.format(System.currentTimeMillis())
    private fun keyDaysAgo(days: Int): String = dayFormat.format(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)
}

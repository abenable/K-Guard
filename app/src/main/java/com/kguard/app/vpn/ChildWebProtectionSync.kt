package com.kguard.app.vpn

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.kguard.app.R
import com.kguard.app.service.KGuardMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs on the paired child's phone. Keeps the on-device DNS filter in line with what the parent set:
 *
 * 1. Listens to the child profile (age group, category rules) and `webProtection/{childId}` (on/off,
 *    always-block, always-allow, educational sites) and writes them into [VpnConfigStore]. The VPN service
 *    reads that store on every lookup, so a parent's change applies to the very next lookup.
 * 2. Starts or stops [KGuardVpnService] when the parent turns protection on or off.
 * 3. If Android needs the one-time VPN permission on this phone, posts a notification that opens
 *    [com.kguard.app.ui.screens.VpnConsentActivity].
 * 4. If protection stops while the parent wants it on (for example the child disconnects it in Android's
 *    settings), restarts it and reports `turnedOffOnDevice` so the parent is told.
 * 5. Reports the real state back to Firestore so the parent's screen shows what is actually happening.
 *
 * Started by the companion service. If it is not running, the filter keeps enforcing the last settings it
 * received, so protection never depends on a network connection.
 */
object ChildWebProtectionSync {
    private const val TAG = "KGuardWeb"
    private const val CONSENT_NOTIFICATION_ID = 4712
    /** Do not fight another VPN app in a tight loop: at most one restart per this interval. */
    private const val REASSERT_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var job: Job? = null
    private var activeKey: String? = null

    @Volatile private var desiredOn = false

    /** The parent has web protection on for this child (as last received; survives offline via Firestore cache). */
    val wantsOn: Boolean get() = desiredOn
    @Volatile private var stoppingOnPurpose = false
    @Volatile private var lastReassertAt = 0L
    private var lastReported: WebDeviceStatus? = null

    /** Safe to call repeatedly; restarts only when the family or child changes. */
    @Synchronized
    fun start(context: Context, familyId: String, childId: String) {
        val key = "$familyId/$childId"
        if (key == activeKey && job?.isActive == true) return
        job?.cancel()
        activeKey = key
        val app = context.applicationContext
        Log.i(TAG, "Child web protection sync started for $key")
        job = scope.launch {
            launch { followParentSettings(app, familyId, childId) }
            launch { watchFilterState(app, familyId, childId) }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        activeKey = null
        lastReported = null
    }

    /** Called by the consent screen once the child's phone has granted (or refused) the VPN permission. */
    fun onConsentResult(context: Context, granted: Boolean) {
        if (granted && desiredOn) startFilter(context.applicationContext)
        reportCurrent(context.applicationContext)
    }

    // ---------------------------------------------------------------- parent -> child
    private suspend fun followParentSettings(context: Context, familyId: String, childId: String) {
        val store = VpnConfigStore(context)
        combine(childProfile(familyId, childId), RemoteWebProtection.observe(familyId, childId)) { child, remote -> child to remote }
            .collect { (child, remote) ->
                store.applyRemote(familyId, childId, child.first, child.second, remote.settings)
                desiredOn = remote.settings.enabled
                Log.d(TAG, "Applied parent settings: enabled=${remote.settings.enabled}, block=${remote.settings.customBlock.size}, allow=${remote.settings.customAllow.size}")
                reconcile(context)
            }
    }

    /** The child's age group and category rules, from `families/{familyId}/children/{childId}`. */
    @Suppress("UNCHECKED_CAST")
    private fun childProfile(familyId: String, childId: String): Flow<Pair<String, Map<String, String>>> = callbackFlow {
        val reg = FirebaseFirestore.getInstance().document("families/$familyId/children/$childId")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "child profile listener error", err)
                    return@addSnapshotListener // keep the last applied rules
                }
                val data = snap?.data ?: return@addSnapshotListener
                val age = data["ageProfile"] as? String ?: return@addSnapshotListener
                val rules = (data["categoryRules"] as? Map<String, Any?>)
                    ?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap() ?: emptyMap()
                trySend(age to rules)
            }
        awaitClose { reg.remove() }
    }

    private suspend fun reconcile(context: Context) = lock.withLock {
        val running = VpnState.running.value
        when {
            desiredOn && !running -> startFilter(context)
            !desiredOn && running -> {
                stoppingOnPurpose = true
                VpnConfigStore(context).setEnabled(false)
                KGuardVpnService.stop(context)
            }
            else -> Unit
        }
        if (!desiredOn) NotificationManagerCompat.from(context).cancel(CONSENT_NOTIFICATION_ID)
        reportCurrent(context)
    }

    private fun startFilter(context: Context) {
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not granted on this phone yet; asking the child to allow it")
            showConsentNotification(context)
            return
        }
        NotificationManagerCompat.from(context).cancel(CONSENT_NOTIFICATION_ID)
        VpnConfigStore(context).setEnabled(true)
        runCatching { KGuardVpnService.start(context) }
            .onFailure { Log.e(TAG, "Could not start web protection", it) }
    }

    // ---------------------------------------------------------------- child -> parent
    private suspend fun watchFilterState(context: Context, familyId: String, childId: String) {
        VpnState.running.drop(1).collect { running ->
            if (!running && desiredOn && !stoppingOnPurpose) {
                Log.w(TAG, "Web protection stopped on the phone while the parent wants it on")
                reportCurrent(context, turnedOff = true)
                val wait = (lastReassertAt + REASSERT_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(3_000L)
                delay(wait)
                if (desiredOn && !VpnState.running.value) {
                    lastReassertAt = System.currentTimeMillis()
                    lock.withLock { startFilter(context) }
                }
            } else {
                if (running || !desiredOn) stoppingOnPurpose = false
                reportCurrent(context)
            }
        }
    }

    private fun reportCurrent(context: Context, turnedOff: Boolean = false) {
        val store = VpnConfigStore(context).load()
        val familyId = store.familyId ?: return
        val childId = store.childId ?: return
        val running = VpnState.running.value
        val status = WebDeviceStatus(
            running = running,
            needsConsent = desiredOn && !running && VpnService.prepare(context) != null,
            turnedOffOnDevice = turnedOff && !running
        )
        synchronized(this) {
            if (status == lastReported) return
            lastReported = status
        }
        scope.launch {
            runCatching { RemoteWebProtection.reportDeviceStatus(familyId, childId, status) }
                .onFailure { Log.w(TAG, "Could not report web protection status (will retry on next change)", it); synchronized(this@ChildWebProtectionSync) { lastReported = null } }
        }
    }

    private fun showConsentNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        KGuardMessagingService.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, CONSENT_NOTIFICATION_ID,
            Intent(context, com.kguard.app.ui.screens.VpnConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, KGuardMessagingService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Finish turning on web protection")
            .setContentText("Your parent turned on web protection. Tap, then choose OK to allow it on this phone.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your parent turned on web protection. Tap, then choose OK to allow it on this phone."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(CONSENT_NOTIFICATION_ID, n)
    }
}

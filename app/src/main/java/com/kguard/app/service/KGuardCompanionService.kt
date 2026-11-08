package com.kguard.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.kguard.app.MainActivity
import com.kguard.app.R
import com.kguard.app.receiver.KGuardDeviceAdminReceiver
import com.kguard.app.screentime.AppInventory
import com.kguard.app.screentime.AppRules
import com.kguard.app.screentime.LocalClock
import com.kguard.app.screentime.LockDecision
import com.kguard.app.screentime.LockEvaluator
import com.kguard.app.screentime.LockKind
import com.kguard.app.screentime.LockOverlay
import com.kguard.app.screentime.ScreenPolicy
import com.kguard.app.screentime.UsageMath
import com.kguard.app.screentime.UsageReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Screen time and device control engine on the paired child's phone. Runs as a foreground service and
 * restarts after a reboot (see BootCompleteReceiver).
 *
 * Source of truth: the parent writes rules to `families/{familyId}/devices/{childId}` (see [ScreenPolicy]);
 * this service only ever writes telemetry fields there, so the two never overwrite each other.
 *
 * Every second it works out whether the phone must be locked ([LockEvaluator]) and enforces it with a
 * full-screen overlay ([LockOverlay]); when the phone is not locked it applies per-app rules ([AppRules]).
 * It reports usage, the current app, battery, lock state and permission status to the parent, reports
 * installs and uninstalls ([AppInventory]), and runs one-shot commands such as the alarm.
 */
class KGuardCompanionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Job? = null
    private val db get() = FirebaseFirestore.getInstance()

    private lateinit var reader: UsageReader
    private lateinit var overlay: LockOverlay
    private lateinit var inventory: AppInventory

    // Identity, resolved from users/{uid}.
    @Volatile private var familyId: String? = null
    @Volatile private var childId: String? = null
    @Volatile private var childName: String = "your child"

    // Parent rules and per-app decisions, kept current by Firestore listeners.
    @Volatile private var policy = ScreenPolicy()
    @Volatile private var appPolicies: Map<String, AppRules.AppPolicy> = emptyMap()
    private val listeners = mutableListOf<ListenerRegistration>()

    // Live state.
    @Volatile private var usage: UsageMath.Result? = null
    private var usageAt = 0L
    @Volatile private var foreground: String? = null
    private var lastPoll = 0L
    private var decision = LockDecision(LockKind.NONE)
    private var lastReported: Map<String, Any?> = emptyMap()
    private var lastTelemetryAt = 0L
    private var lastHistoryAt = 0L
    private var blockedPkg: String? = null
    /** When web protection was first seen off while the parent wants it on; 0 while it's fine. */
    private var webOffSince = 0L
    private var blockShownAt = 0L
    @Volatile private var foregroundActivity: String? = null
    private var lastUninstallLockAt = 0L
    private val firedWarnings = mutableSetOf<String>()
    private var warningsDay = ""
    private var alarm: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null

    @Volatile private var wakeUp = false
    private var receiversRegistered = false

    /** Signing out on this phone ends protection for that account: stop listening and enforcing. */
    private val authListener = FirebaseAuth.AuthStateListener { a ->
        if (a.currentUser == null && engine != null) {
            Log.i(TAG, "Signed out; stopping screen time protection.")
            com.kguard.app.vpn.ChildWebProtectionSync.stop()
            stopSelf()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    wakeUp = true
                    // Without the overlay permission, fall back to switching the screen straight back off.
                    if (decision.locked && !overlay.canDraw()) KGuardDeviceAdminReceiver.lockScreenNow(context)
                }
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_ADDED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            scope.launch { runCatching { inventory.onPackageChanged() } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        reader = UsageReader(this)
        overlay = LockOverlay(this)
        inventory = AppInventory(this)
        FirebaseAuth.getInstance().addAuthStateListener(authListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_STOP_ALARM -> { stopAlarm(); return START_STICKY }
            ACTION_SYNC -> { wakeUp = true; lastTelemetryAt = 0L }
        }
        // Keep showing the last real status; a restart or sync request must not reset it to "Starting…".
        startInForeground(lastNotificationText ?: "Starting protection…")
        isRunning = true
        registerReceivers()
        if (engine?.isActive != true) engine = scope.launch { run() }
        return START_STICKY
    }

    // ---------------------------------------------------------------- engine
    private suspend fun run() {
        if (!resolveIdentity()) {
            Log.i(TAG, "This phone is not a paired child device; stopping.")
            withContext(Dispatchers.Main) { stopSelf() }
            return
        }
        val fid = familyId!!
        val cid = childId!!
        // Make sure this phone can receive pushes for this account (approval results, alarms).
        scope.launch { runCatching { com.kguard.app.data.repository.FamilyRepository.registerPushToken() }
            .onFailure { Log.w(TAG, "Push token registration failed; will retry on next start", it) } }
        attachListeners(fid, cid)
        com.kguard.app.vpn.ChildWebProtectionSync.start(this, fid, cid)

        var tick = 0L
        while (scope.isActive) {
            runCatching { step(fid, cid, tick) }.onFailure { Log.e(TAG, "Enforcement step failed", it) }
            if (tick % 300 == 0L) scope.launch { runCatching { inventory.reconcile() } } // retry queued reports
            tick++
            // Sleep a second, but react at once to screen-on and parent changes.
            var waited = 0
            while (waited < 1000 && !wakeUp) { delay(100); waited += 100 }
            wakeUp = false
        }
    }

    private suspend fun step(fid: String, cid: String, tick: Long) {
        val now = System.currentTimeMillis()
        val clock = LocalClock.now()
        val screenOn = reader.isScreenOn()

        // Usage: full recount every 15 s, cheap foreground check every second.
        if (now - usageAt > 15_000 || usage == null) {
            usage = reader.today()
            usageAt = now
            foreground = usage?.currentPackage
            lastPoll = now
        } else if (screenOn) {
            reader.latestForegroundChange(lastPoll - 2_000)?.let { foreground = it.first }
            reader.latestForegroundActivity(lastPoll - 2_000)?.let { foregroundActivity = it.second }
            lastPoll = now
        }
        if (!screenOn) { foreground = null; foregroundActivity = null }
        val used = usage?.totalMinutes ?: 0

        val previous = decision
        decision = LockEvaluator.evaluate(policy, clock, used)
        if (decision.kind != previous.kind) onLockChanged(previous, decision)

        withContext(Dispatchers.Main) { enforce(clock, screenOn) }

        warnIfClose(clock, used)
        if (tick % 5 == 0L) updateNotification(clock, used)
        maybeReport(fid, cid, clock, now)
    }

    private fun enforce(clock: LocalClock, screenOn: Boolean) {
        // Uninstall guard. While K-Guard's device admin is on, uninstalling it (or switching protection off)
        // has to pass Android's "deactivate device admin" screen. Android lets no app draw over that screen,
        // so unless the parent's PIN was entered in K-Guard (see UninstallGuard), lock the phone at once.
        val onDeactivateScreen = screenOn && foregroundActivity?.endsWith("DeviceAdminAdd") == true &&
            KGuardDeviceAdminReceiver.isAdminActive(this)
        if (onDeactivateScreen && !com.kguard.app.screentime.UninstallGuard.isAllowed(this)) {
            val now = System.currentTimeMillis()
            if (now - lastUninstallLockAt > 3_000) {
                lastUninstallLockAt = now
                Log.w(TAG, "Attempt to switch off K-Guard without the PIN; locking the phone")
                // Put K-Guard's PIN prompt on top of the deactivate screen, then lock, so that after unlocking
                // the child sees the prompt rather than being locked out again and again.
                com.kguard.app.screentime.UninstallGuard.promptRequested.value = true
                KGuardDeviceAdminReceiver.lockScreenNow(this)
                foregroundActivity = null
                showRemovalPrompt()
            }
            return
        }

        val fg = foreground
        if (decision.locked) {
            blockedPkg = null
            if (fg != null && fg == overlay.dialerPackage) { overlay.hide(); return } // emergency call
            if (System.currentTimeMillis() < LockOverlay.pinUnlockedUntil) { overlay.hide(); return } // parent PIN entered
            val heading = when (decision.kind) {
                LockKind.BEDTIME -> "Bedtime"
                LockKind.LIMIT -> "Screen time is up"
                else -> "Phone locked"
            }
            if (!overlay.show(LockOverlay.Mode.PHONE_LOCK, heading, decision.reason, decision.untilMillis, childName, decision.kind) && screenOn) {
                KGuardDeviceAdminReceiver.lockScreenNow(this) // no overlay permission: keep switching the screen off
            }
            return
        }

        // Web protection switched off on the phone while the parent wants it on: no filter, no phone.
        // The grace period covers the sync's own automatic restart; Android's VPN prompt, K-Guard itself and
        // the phone app stay usable so the child can turn it back on or make a call.
        if (enforceWebProtection(fg, screenOn)) return

        // Phone not locked: per-app rules for whatever is in front.
        if (!screenOn) { overlay.hide(); blockedPkg = null; return }
        if (fg == null) {
            // Back on the home screen: leave the "why" message up briefly, then clear it.
            if (overlay.isShowing && (blockedPkg == null || System.currentTimeMillis() - blockShownAt > BLOCK_NOTICE_MS)) {
                overlay.hide(); blockedPkg = null
            }
            return
        }
        val verdict = AppRules.check(
            appName = appPolicies[fg]?.appName?.takeIf { it.isNotBlank() } ?: reader.appLabel(fg),
            policy = appPolicies[fg],
            androidCategory = reader.appCategory(fg),
            usedMinutesToday = usage?.minutesFor(fg) ?: 0,
            schoolTime = LockEvaluator.isSchoolTime(policy, clock)
        )
        when (verdict) {
            is AppRules.Verdict.Blocked -> {
                if (blockedPkg != fg) { overlay.goHome(); blockedPkg = fg; blockShownAt = System.currentTimeMillis() }
                overlay.show(LockOverlay.Mode.APP_BLOCK, verdict.title, verdict.message, null, childName)
            }
            AppRules.Verdict.Allowed -> if (overlay.isShowing) { overlay.hide(); blockedPkg = null }
        }
    }

    /** Returns true when the web-protection lock is handling the screen. */
    private fun enforceWebProtection(fg: String?, screenOn: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val off = com.kguard.app.vpn.ChildWebProtectionSync.wantsOn && !com.kguard.app.vpn.VpnState.running.value
        webOffSince = if (!off) 0L else webOffSince.takeIf { it > 0 } ?: now
        if (!off || !screenOn || now - webOffSince < WEB_OFF_GRACE_MS || now < LockOverlay.pinUnlockedUntil) return false
        blockedPkg = null
        val stepAside = now < LockOverlay.stepAsideUntil || fg == packageName || fg == VPN_DIALOGS_PACKAGE ||
            (fg != null && fg == overlay.dialerPackage)
        if (stepAside) {
            overlay.hide()
        } else if (!overlay.show(
                LockOverlay.Mode.PHONE_LOCK,
                "Web protection is off",
                "Your parent keeps web protection on for this phone. Turn it back on to keep using it.",
                null, childName, webOff = true
            )
        ) {
            KGuardDeviceAdminReceiver.lockScreenNow(this) // no overlay permission
        }
        return true
    }

    private fun onLockChanged(old: LockDecision, new: LockDecision) {
        Log.i(TAG, "Lock ${old.kind} -> ${new.kind}: ${new.reason}")
        lastTelemetryAt = 0L // report at once
        if (new.locked && (new.kind == LockKind.REMOTE || new.kind == LockKind.BEDTIME)) {
            KGuardDeviceAdminReceiver.lockScreenNow(this) // switch the screen off when the lock starts
        }
        if (!new.locked) scope.launch(Dispatchers.Main) { overlay.hide() }
    }

    // ---------------------------------------------------------------- identity & listeners
    private suspend fun resolveIdentity(): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        return runCatching {
            val user = db.document("users/$uid").get().await()
            if (user.getString("role") != "child") return false
            familyId = user.getString("familyId") ?: return false
            childId = user.getString("childId") ?: return false
            db.document("families/$familyId/children/$childId").get().await().getString("name")?.let { childName = it }
            true
        }.getOrElse { Log.e(TAG, "Could not resolve child identity", it); false }
    }

    private fun attachListeners(fid: String, cid: String) {
        listeners.forEach { it.remove() }
        listeners.clear()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        listeners += db.document("families/$fid/devices/$cid").addSnapshotListener { snap, err ->
            if (err != null) { Log.w(TAG, "Rules listener error", err); return@addSnapshotListener }
            val next = ScreenPolicy.parse(snap?.data)
            if (next != policy) { policy = next; wakeUp = true; lastTelemetryAt = 0L }
        }

        listeners += db.document("families/$fid/children/$cid").addSnapshotListener { snap, _ ->
            snap?.getString("name")?.let { childName = it }
        }

        listeners += db.collection("families/$fid/apps").whereEqualTo("childLinkedUid", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "App rules listener error", err); return@addSnapshotListener }
                appPolicies = snap?.documents.orEmpty().mapNotNull { d ->
                    val pkg = d.getString("packageName") ?: return@mapNotNull null
                    if (d.getBoolean("installed") == false) return@mapNotNull null
                    pkg to AppRules.AppPolicy(
                        status = d.getString("approvalStatus") ?: "APPROVED",
                        dailyLimitMinutes = d.getLong("dailyLimitMinutes")?.toInt() ?: 0,
                        appName = d.getString("appName") ?: pkg
                    )
                }.toMap()
                wakeUp = true
            }

        listeners += db.collection("families/$fid/commands")
            .whereEqualTo("childId", cid)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.w(TAG, "Commands listener error", err); return@addSnapshotListener }
                for (doc in snap?.documents.orEmpty()) {
                    val sentAt = doc.getLong("timestamp") ?: 0L
                    val action = doc.getString("action") ?: ""
                    val fresh = System.currentTimeMillis() - sentAt < COMMAND_TTL_MS
                    if (fresh && action == "sound_alarm") scope.launch(Dispatchers.Main) { playAlarm() }
                    // Lock, unlock, limits and schedules are applied from the rules document; the command
                    // record is the parent's audit log and delivery receipt.
                    doc.reference.update(
                        mapOf("status" to if (fresh) "executed" else "expired", "executedAt" to FieldValue.serverTimestamp())
                    )
                }
                wakeUp = true
            }
    }

    // ---------------------------------------------------------------- reporting
    private fun maybeReport(fid: String, cid: String, clock: LocalClock, now: Long) {
        val u = usage
        val perms = permissionStatus()
        val core = mapOf(
            "lockState" to decision.stateId,
            "lockReason" to decision.reason.takeIf { decision.locked },
            "lockUntil" to decision.untilMillis,
            "permissions" to perms,
            "currentForegroundPackage" to foreground
        )
        val changed = core != lastReported
        if (!changed && now - lastTelemetryAt < TELEMETRY_INTERVAL_MS) return
        lastReported = core
        lastTelemetryAt = now

        val (battery, charging) = battery()
        val apps = u?.perAppMillis.orEmpty().entries.sortedByDescending { it.value }.take(40).map { (pkg, ms) ->
            val p = appPolicies[pkg]
            mapOf(
                "packageName" to pkg,
                "appName" to (p?.appName ?: reader.appLabel(pkg)),
                "category" to UsageReader.categoryLabel(reader.appCategory(pkg)),
                "usedMinutes" to (ms / 60_000L).toInt(),
                "dailyLimitMinutes" to p?.dailyLimitMinutes,
                "isBlocked" to (p?.status == "REJECTED" || p?.status == "PENDING_REVIEW"),
                "isCurrentlyActive" to (pkg == foreground)
            )
        }
        val telemetry = HashMap<String, Any?>(core).apply {
            put("childId", cid)
            put("childName", childName)
            put("linkedUid", FirebaseAuth.getInstance().currentUser?.uid)
            put("deviceName", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            put("deviceModel", Build.MODEL)
            put("androidVersion", "Android ${Build.VERSION.RELEASE}")
            put("usedScreenTimeMinutes", u?.totalMinutes)
            put("usageDate", clock.today)
            put("pickupsCount", u?.unlocks)
            put("hourlyUsageMinutes", u?.hourlyMinutes)
            put("appUsages", apps)
            put("currentForegroundApp", foreground?.let { appPolicies[it]?.appName ?: reader.appLabel(it) })
            put("batteryPercent", battery)
            put("isCharging", charging)
            put("networkName", networkName())
            put("isDeviceAdminActive", perms["deviceAdmin"])
            put("lastSyncedAt", FieldValue.serverTimestamp())
        }
        db.document("families/$fid/devices/$cid").set(telemetry, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Telemetry write failed (will retry)", it); lastReported = emptyMap() }

        if (u != null && now - lastHistoryAt > HISTORY_INTERVAL_MS) {
            lastHistoryAt = now
            db.document("families/$fid/devices/$cid/days/${clock.today}").set(
                mapOf(
                    "date" to clock.today,
                    "usedScreenTimeMinutes" to u.totalMinutes,
                    "pickupsCount" to u.unlocks,
                    "hourlyUsageMinutes" to u.hourlyMinutes,
                    "appUsages" to apps.take(20),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }
    }

    /** Everything this phone needs for full protection; the parent sees what is missing. */
    private fun permissionStatus(): Map<String, Boolean> = mapOf(
        "usageAccess" to reader.hasAccess(),
        "overlay" to overlay.canDraw(),
        "deviceAdmin" to KGuardDeviceAdminReceiver.isAdminActive(this),
        "notifications" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
        "batteryUnrestricted" to (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    ) + alwaysOnVpnStatus()

    /** Reported only while the parent wants web protection and the running filter can tell (Android 10+). */
    private fun alwaysOnVpnStatus(): Map<String, Boolean> {
        if (!com.kguard.app.vpn.ChildWebProtectionSync.wantsOn) return emptyMap()
        val on = com.kguard.app.vpn.KGuardVpnService.isAlwaysOn() ?: return emptyMap()
        return mapOf("alwaysOnVpn" to on)
    }

    private fun battery(): Pair<Int?, Boolean?> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        return pct to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun networkName(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "Unknown"
        var caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        // With web protection on, the active network is K-Guard's own VPN; report the real one underneath.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            @Suppress("DEPRECATION")
            caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
                .firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                ?: return "Connected"
        }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // The carrier name needs no extra permission (the Wi-Fi network name would need location).
                val carrier = (getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                    ?.networkOperatorName?.takeIf { it.isNotBlank() }
                if (carrier != null) "Mobile data · $carrier" else "Mobile data"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    // ---------------------------------------------------------------- child-facing notices
    private fun warnIfClose(clock: LocalClock, used: Int) {
        if (warningsDay != clock.today) { warningsDay = clock.today; firedWarnings.clear() }
        if (decision.locked) return
        val toBed = LockEvaluator.minutesToBedtime(policy, clock)
        val limit = policy.limitFor(clock.today)
        val left = if (limit > 0) limit - used else null
        val lockIn = policy.remoteLock.takeIf { it.locked }?.fromMillis
            ?.let { ((it - clock.nowMillis + 59_999) / 60_000L).toInt() }?.takeIf { it > 0 }
        for (m in listOf(10, 1)) {
            if (lockIn != null && lockIn in 1..m && firedWarnings.add("lock$m-${policy.remoteLock.fromMillis}")) {
                notifyChild("Phone locking soon", "Your parent set this phone to lock in $lockIn minute${if (lockIn == 1) "" else "s"}.")
            }
            if (toBed != null && toBed in 1..m && firedWarnings.add("bed$m")) {
                notifyChild("Bedtime soon", "The phone locks for bedtime in $toBed minute${if (toBed == 1) "" else "s"}.")
            }
            if (left != null && left in 1..m && firedWarnings.add("limit$m")) {
                notifyChild("Screen time almost up", "You have $left minute${if (left == 1) "" else "s"} of screen time left today.")
            }
        }
    }

    /**
     * Android 10+ won't let a background service open an activity, but a full-screen notification may: with
     * the phone just locked, it puts K-Guard (and its parent-PIN prompt) in front of the deactivate screen,
     * so the child sees the prompt after unlocking instead of being locked out again.
     */
    private fun showRemovalPrompt() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(GUARD_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(GUARD_CHANNEL_ID, "Protection alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown when someone tries to remove K-Guard from this phone"
                }
            )
        }
        val open = Intent(this, com.kguard.app.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, GUARD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("K-Guard is protected")
            .setContentText("Only a parent can remove K-Guard. Tap to enter the parent PIN.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        val canNotify = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!canNotify) { Log.w(TAG, "Notifications are off; the removal prompt can't be shown"); return }
        runCatching { NotificationManagerCompat.from(this).notify(GUARD_NOTIFICATION_ID, n) }
            .onFailure { Log.w(TAG, "Could not show the removal prompt", it) }
    }

    private fun notifyChild(title: String, body: String) {
        KGuardMessagingService.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(this, KGuardMessagingService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        NotificationManagerCompat.from(this).notify(title.hashCode(), n)
    }

    // ---------------------------------------------------------------- alarm
    private fun playAlarm() {
        if (alarm != null) return
        try {
            val am = getSystemService(AudioManager::class.java)
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            alarm = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                prepare()
                start()
            }
            showAlarmNotification()
            scope.launch { delay(ALARM_MS); withContext(Dispatchers.Main) { stopAlarm() } }
        } catch (e: Exception) {
            Log.e(TAG, "Could not play the alarm", e)
            stopAlarm()
        }
    }

    private fun stopAlarm() {
        alarm?.let { runCatching { it.stop() }; it.release() }
        alarm = null
        savedAlarmVolume?.let { v -> runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedAlarmVolume = null
        NotificationManagerCompat.from(this).cancel(ALARM_NOTIFICATION_ID)
    }

    private fun showAlarmNotification() {
        KGuardMessagingService.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KGuardCompanionService::class.java).setAction(ACTION_STOP_ALARM), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, KGuardMessagingService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Your parent is trying to find this phone")
            .setContentText("Tap Stop to silence it.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        NotificationManagerCompat.from(this).notify(ALARM_NOTIFICATION_ID, n)
    }

    // ---------------------------------------------------------------- service plumbing
    private fun registerReceivers() {
        if (receiversRegistered) return
        val screen = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        val pkgs = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED); addDataScheme("package")
        }
        // System broadcasts reach non-exported receivers.
        ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, packageReceiver, pkgs, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Screen time protection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that K-Guard is applying your family's screen time rules."
            })
        }
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("K-Guard is protecting this phone")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNotification(clock: LocalClock, used: Int) {
        val limit = policy.limitFor(clock.today)
        val text = when {
            decision.locked -> decision.reason
            !reader.hasAccess() -> "Usage access is needed to track screen time."
            limit > 0 -> "${LockEvaluator.formatMinutes((limit - used).coerceAtLeast(0))} of screen time left today"
            else -> "${LockEvaluator.formatMinutes(used)} of screen time today"
        }
        // Re-posting an unchanged notification still lights the screen edge and counts towards Android's
        // spam limit (which then mutes every K-Guard alert), so only post when the text changes.
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
    private var lastNotificationText: String? = null

    override fun onDestroy() {
        FirebaseAuth.getInstance().removeAuthStateListener(authListener)
        isRunning = false
        listeners.forEach { it.remove() }
        listeners.clear()
        if (receiversRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { unregisterReceiver(packageReceiver) }
            receiversRegistered = false
        }
        overlay.hide()
        stopAlarm()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "KGuardScreenTime"
        const val CHANNEL_ID = "kguard_companion_service_channel"
        const val NOTIFICATION_ID = 2001
        private const val ALARM_NOTIFICATION_ID = 2002
        const val ACTION_START = "com.kguard.app.action.START_COMPANION"
        const val ACTION_STOP = "com.kguard.app.action.STOP_COMPANION"
        const val ACTION_SYNC = "com.kguard.app.action.SYNC_COMPANION"
        private const val ACTION_STOP_ALARM = "com.kguard.app.action.STOP_ALARM"
        const val EXTRA_FAMILY_ID = "extra_family_id"
        const val EXTRA_CHILD_ID = "extra_child_id"
        private const val TELEMETRY_INTERVAL_MS = 60_000L
        private const val HISTORY_INTERVAL_MS = 5 * 60_000L
        private const val COMMAND_TTL_MS = 10 * 60_000L
        private const val ALARM_MS = 30_000L
        private const val GUARD_CHANNEL_ID = "kguard_protection"
        private const val GUARD_NOTIFICATION_ID = 2003
        private const val BLOCK_NOTICE_MS = 4_000L
        private const val WEB_OFF_GRACE_MS = 20_000L
        /** Android's VPN consent / manage dialogs. */
        private const val VPN_DIALOGS_PACKAGE = "com.android.vpndialogs"

        @Volatile
        var isRunning = false
            private set

        /**
         * Starts protection on a paired child's phone. The ids are optional: the service reads them from the
         * signed-in child's profile and stops by itself on a parent's phone.
         *
         * Returns false when Android refuses: from the background on Android 12+, a foreground service may only
         * start in exempt cases (unrestricted battery, a high-priority push, boot, a user tap).
         */
        fun startCompanionService(context: Context, familyId: String? = null, childId: String? = null): Boolean {
            val intent = Intent(context, KGuardCompanionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FAMILY_ID, familyId)
                putExtra(EXTRA_CHILD_ID, childId)
            }
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Could not start screen time protection", it) }
                .isSuccess
        }

        /** Asks a running service to re-check and report now (for example after a permission changes). */
        fun requestSync(context: Context) {
            if (!isRunning) return
            runCatching { context.startService(Intent(context, KGuardCompanionService::class.java).setAction(ACTION_SYNC)) }
        }

        fun stopCompanionService(context: Context) {
            runCatching { context.startService(Intent(context, KGuardCompanionService::class.java).setAction(ACTION_STOP)) }
        }
    }
}

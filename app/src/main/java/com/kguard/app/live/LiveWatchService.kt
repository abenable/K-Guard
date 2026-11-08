package com.kguard.app.live

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.kguard.app.MainActivity
import com.kguard.app.R
import com.kguard.app.data.model.AgeProfile
import com.kguard.app.data.repository.UserPreferencesRepository
import com.kguard.app.screentime.UsageReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * K-Guard Live. With the child's consent (Android's screen-sharing prompt), it looks at the screen every few
 * seconds and asks the AI whether what is showing suits the child's age. Nothing is drawn over the app in use
 * apart from a glow on the left and right edges; the session time shows in the notification.
 *
 * - HARMFUL: the app is sent to the background behind a blurred copy of the screen with a friendly card
 *   (see LiveShieldOverlay); the child can only go back. The parent is alerted.
 * - CAUTION: the screen is blurred with the same card; if the parent allows tap-to-reveal for this age, the
 *   child may continue, and that screen isn't blurred again. The parent is alerted.
 * Screenshots are analysed in memory and never stored or uploaded anywhere except to the AI for the check.
 */
class LiveWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private lateinit var glow: EdgeGlowOverlay
    private lateinit var shield: LiveShieldOverlay
    private val analyzer by lazy { LiveScreenAnalyzer(this) }
    private var startedAt = 0L

    private var familyId: String? = null
    private var childId: String? = null
    private var childName: String? = null
    private var age: AgeProfile = AgeProfile.PRETEENS_8_12

    // Unchanged screens are not sent again; the last verdict is reused for them.
    private var lastHash = 0L
    private var lastVerdict: ScreenVerdict? = null
    /** A milder screen the child chose to see anyway (when allowed); not blurred again while it stays the same. */
    private var revealedHash: Long? = null
    private var allowReveal = false
    private val lastReported = HashMap<String, Long>()
    private var checks = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        glow = EdgeGlowOverlay(this)
        shield = LiveShieldOverlay(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY // already live
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        startedAt = System.currentTimeMillis()
        // Android requires the media-projection foreground service to be running before the projection starts.
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) { Log.e(TAG, "Screen sharing could not start"); stopSelf(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Screen sharing ended by the system")
                stopSelf()
            }
        }, Handler(mainLooper))

        startCapture(mp)
        glow.show()
        running = true
        Log.i(TAG, "K-Guard Live started")

        scope.launch {
            loadChild()
            watchLoop()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val metrics = resources.displayMetrics
        val scale = CAPTURE_WIDTH.toFloat() / metrics.widthPixels
        val w = CAPTURE_WIDTH
        val h = (metrics.heightPixels * scale).toInt().let { it - it % 2 }
        val thread = HandlerThread("kguard-live").also { it.start() }
        captureThread = thread
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = mp.createVirtualDisplay(
            "kguard-live", w, h, metrics.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, Handler(thread.looper)
        )
    }

    private suspend fun loadChild() {
        // Same identity source as the screen time engine: the paired device's user document.
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        val user = uid?.let { runCatching { FirebaseFirestore.getInstance().document("users/$it").get().await() }.getOrNull() }
        if (user?.getString("role") == "child") {
            familyId = user.getString("familyId")
            childId = user.getString("childId")
        }
        val f = familyId; val c = childId
        if (f != null && c != null) {
            runCatching { FirebaseFirestore.getInstance().document("families/$f/children/$c").get().await() }
                .getOrNull()?.let { doc ->
                    childName = doc.getString("name")
                    age = AgeProfile.fromId(doc.getString("ageProfile"))
                    // Same rule as the server: only teens and adults may reveal, and only if the parent allows it.
                    val ageAllows = age == AgeProfile.TEENS_13_17 || age == AgeProfile.ADULTS_18_PLUS
                    allowReveal = ageAllows && (doc.getBoolean("allowReveal") ?: ageAllows)
                }
        }
        Log.i(TAG, "Watching for ${childName ?: "child"} (${age.id})")
    }

    private suspend fun watchLoop() {
        val power = getSystemService(PowerManager::class.java)
        val usage = UsageReader(this)
        var app: Pair<String?, String?>? = null
        var appSince = System.currentTimeMillis() - 30 * 60_000L
        while (scope.isActive) {
            delay(INTERVAL_MS)
            if (!power.isInteractive || shield.isShowing) continue
            usage.latestForegroundActivity(appSince)?.let { app = it; appSince = System.currentTimeMillis() - 2_000 }
            val pkg = app?.first
            // K-Guard's own screens, the home screen and the system UI only show icons and wallpaper.
            if (pkg == packageName || pkg in usage.excluded) continue

            val frame = grabFrame() ?: continue
            val hash = dHash(frame)
            val verdict = if (lastVerdict != null && hamming(hash, lastHash) <= SAME_SCREEN_BITS) {
                lastVerdict
            } else {
                val label = pkg?.let { usage.appLabel(it) }
                val v = withContext(Dispatchers.IO) { analyzer.analyze(frame, age, label) { toJpeg(frame) } } ?: continue
                checks++
                Log.d(TAG, "Check #$checks ${label ?: "?"}: ${v.level} ${v.category} – ${v.reason}")
                lastHash = hash; lastVerdict = v
                v
            }
            verdict?.let { act(it, pkg, frame, hash) }
            frame.recycle()
        }
    }

    private suspend fun act(v: ScreenVerdict, pkg: String?, frame: Bitmap, hash: Long) {
        when (v.level) {
            ScreenVerdict.Level.SAFE -> withContext(Dispatchers.Main) { glow.setAlert(false) }
            ScreenVerdict.Level.CAUTION -> {
                val revealed = revealedHash?.let { hamming(it, hash) <= SAME_SCREEN_BITS } == true
                withContext(Dispatchers.Main) {
                    glow.setAlert(true)
                    if (!revealed) {
                        shield.show(frame, v.category, harmful = false, aiMessage = v.childMessage, childName = childName,
                            canReveal = allowReveal,
                            onGoBack = { shield.goHome(); lastVerdict = null },
                            onReveal = { revealedHash = hash; Log.i(TAG, "Child chose to continue (${v.category})") })
                    }
                }
                if (shouldReport(v, pkg)) report(v, pkg, decision = if (allowReveal) "BLUR" else "WARN", risk = 0.6)
            }
            ScreenVerdict.Level.HARMFUL -> {
                withContext(Dispatchers.Main) {
                    glow.setAlert(true)
                    // The blurred copy stays on screen while the app itself goes to the background (a video stops).
                    shield.show(frame, v.category, harmful = true, aiMessage = v.childMessage, childName = childName,
                        canReveal = false, onGoBack = {}, onReveal = {})
                    shield.goHome()
                }
                if (shouldReport(v, pkg)) report(v, pkg, decision = "BLOCK", risk = 0.9)
                // After going home the screen changed; forget the verdict so the next frame is checked fresh.
                lastVerdict = null
            }
        }
    }

    /** One alert per category and app every few minutes, so a long video doesn't flood the parent. */
    private fun shouldReport(v: ScreenVerdict, pkg: String?): Boolean {
        val key = "${v.level}|${v.category}|$pkg"
        val now = System.currentTimeMillis()
        if (now - (lastReported[key] ?: 0L) < REPORT_GAP_MS) return false
        lastReported[key] = now
        return true
    }

    private fun report(v: ScreenVerdict, pkg: String?, decision: String, risk: Double) {
        val f = familyId ?: return
        val c = childId ?: return
        val label = pkg?.let { UsageReader(this).appLabel(it) }
        FirebaseFirestore.getInstance().collection("families/$f/events").add(
            hashMapOf(
                "childId" to c,
                "childName" to (childName ?: "Your child"),
                "ageProfile" to age.id,
                "contentType" to "screen",
                "category" to v.category,
                "riskScore" to risk,
                "decision" to decision,
                "context" to "harmful",
                "explanation" to (label?.let { "In $it: " } ?: "") + v.reason,
                "source" to "live_ai",
                "snippet" to null,
                "storagePath" to null,
                "status" to "open",
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).addOnFailureListener { Log.e(TAG, "Could not record the alert", it) }
    }

    // ------------------------------------------------------------------ frames
    private fun grabFrame(): Bitmap? {
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val full = Bitmap.createBitmap(image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            // Cut off the row padding and the glowing edges.
            val edge = (glow.edgePx * CAPTURE_WIDTH.toFloat() / resources.displayMetrics.widthPixels).toInt() + 1
            val cropped = Bitmap.createBitmap(full, edge, 0, image.width - 2 * edge, image.height)
            if (cropped != full) full.recycle()
            cropped
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a frame", e); null
        } finally {
            image.close()
        }
    }

    private fun toJpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        b.compress(Bitmap.CompressFormat.JPEG, 70, out); out.toByteArray()
    }

    /** 64-bit difference hash: near-identical screens give near-identical hashes. */
    private fun dHash(b: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(b, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val l = luma(small.getPixel(x, y)); val r = luma(small.getPixel(x + 1, y))
            hash = (hash shl 1) or (if (l > r) 1L else 0L)
        }
        small.recycle()
        return hash
    }

    private fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    private fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    // ------------------------------------------------------------------ notifications
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "K-Guard Live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while K-Guard Live is checking the screen"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("K-Guard Live")
            .setContentText("AI safety check is looking at the screen")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        analyzer.close() // frees the on-device model's memory
        glow.hide()
        shield.hide()
        display?.release(); display = null
        reader?.close(); reader = null
        captureThread?.quitSafely(); captureThread = null
        projection?.stop(); projection = null
        Log.i(TAG, "K-Guard Live stopped after $checks checks")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KGuardLive"
        private const val CHANNEL_ID = "kguard_live"
        private const val NOTIFICATION_ID = 4720
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val CAPTURE_WIDTH = 540
        private const val INTERVAL_MS = 5_000L
        private const val SAME_SCREEN_BITS = 6
        private const val REPORT_GAP_MS = 3 * 60_000L

        @Volatile var running = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, LiveWatchService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }
    }
}

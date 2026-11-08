package com.kguard.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kguard.app.MainActivity
import com.kguard.app.R
import com.kguard.app.data.model.FamilyPrivacy
import com.kguard.app.data.model.ModerationDecision
import com.kguard.app.data.model.QuietHours
import com.kguard.app.data.repository.FamilyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.SimpleTimeZone
import java.util.concurrent.atomic.AtomicInteger

/** Receives safety-alert & app-approval pushes for parents and keeps the device's FCM token registered. */
class KGuardMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { FamilyRepository.registerPushToken(token) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // Remote commands: the push only wakes the enforcement engine; it reads the rules and the
        // pending commands from Firestore itself (a high-priority push may start a foreground service).
        if (data["type"] == "remote_lock" || data["type"] == "remote_command") {
            if (KGuardCompanionService.isRunning) KGuardCompanionService.requestSync(this)
            else KGuardCompanionService.startCompanionService(this)
            return
        }

        val title = message.notification?.title ?: data["title"] ?: "K-Guard alert"
        val rawBody = message.notification?.body ?: data["body"] ?: return
        val familyId = data["familyId"]

        if (familyId == null) {
            Log.w(TAG, "No familyId in push payload; showing alert without privacy filtering")
            showAlert(this, title, rawBody, destinationRoute(data["type"]))
            return
        }

        scope.launch {
            val privacy = runCatching { FamilyRepository.privacy(familyId).first() }.getOrNull()
            if (privacy == null) {
                Log.w(TAG, "Could not load privacy settings; showing alert (fail-open)")
                showAlert(this@KGuardMessagingService, title, rawBody, destinationRoute(data["type"]))
                return@launch
            }

            if (!passesPrivacyFilter(privacy, data["decision"])) return@launch

            // includeSnippet gates whether flagged message content is displayed on the device.
            val snippet = data["snippet"]
            val body = if (snippet != null && !privacy.includeSnippet) {
                "New safety alert — open K-Guard for details."
            } else {
                rawBody
            }

            showAlert(this@KGuardMessagingService, title, body, destinationRoute(data["type"]))
        }
    }

    private fun destinationRoute(type: String?): String = when (type) {
        "app_approval", "app_approval_response" -> "app_management"
        "device_protection", "device_silent", "app_activity" -> "screen_time"
        "web_protection_status" -> "web_protection"
        else -> "review_queue"
    }

    /**
     * BLOCK decisions always show. Everything else obeys notifyOn, and quiet hours
     * suppress all non-block alerts during the window.
     */
    private fun passesPrivacyFilter(privacy: FamilyPrivacy, decision: String?): Boolean {
        val level = ModerationDecision.fromString(decision)
        if (level == ModerationDecision.BLOCK) return true

        val allowed = when (privacy.notifyOn) {
            FamilyPrivacy.NOTIFY_ALL -> true
            FamilyPrivacy.NOTIFY_WARN_AND_ABOVE -> level != ModerationDecision.ALLOW
            FamilyPrivacy.NOTIFY_BLOCK_ONLY -> false
            else -> level != ModerationDecision.ALLOW
        }
        if (!allowed) return false
        if (privacy.quietHours.enabled && inQuietHours(System.currentTimeMillis(), privacy.quietHours)) {
            return false
        }
        return true
    }

    /** Parent-local quiet-hours check; the window may cross midnight (e.g. 21:00 → 07:00). */
    private fun inQuietHours(nowUtcMillis: Long, q: QuietHours): Boolean {
        val start = parseHourMinutes(q.start) ?: return false
        val end = parseHourMinutes(q.end) ?: return false
        val cal = Calendar.getInstance(SimpleTimeZone(q.utcOffsetMinutes * 60_000, "UTC"))
        cal.timeInMillis = nowUtcMillis
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start <= end) minutes in start until end else minutes >= start || minutes < end
    }

    private fun parseHourMinutes(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    companion object {
        private const val TAG = "KGuardMessaging"
        const val CHANNEL_ID = "safety_alerts"
        private val notificationId = AtomicInteger(0)

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID, "Safety & Approval Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Alerts when K-Guard flags content or receives child app install requests." }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun showAlert(context: Context, title: String, body: String, destinationRoute: String? = null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel(context)
            val id = notificationId.incrementAndGet()
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (destinationRoute != null) {
                    putExtra("navigate_to", destinationRoute)
                }
            }
            val open = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }
}

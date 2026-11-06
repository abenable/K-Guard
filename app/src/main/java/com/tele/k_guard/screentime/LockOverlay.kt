package com.tele.k_guard.screentime

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tele.k_guard.data.repository.UserPreferencesRepository
import com.tele.k_guard.ui.screens.LockScreenContent
import com.tele.k_guard.ui.screens.LockScreenKind
import com.tele.k_guard.ui.theme.KGuardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen window drawn above every app on the child's phone. This is how a lock is enforced:
 * Android no longer lets a background service open an activity, but an app with "Display over other apps"
 * may show an overlay window at any time. It swallows touches and the back key; Home and Recents cannot open
 * anything underneath because the overlay stays on top of whatever is shown.
 *
 * The content is the app's Compose lock screen ([LockScreenContent]). An emergency call button is always
 * available, and the parent's PIN unlocks the phone locally for [PIN_UNLOCK_MS].
 */
class LockOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var root: FrameLayout? = null
    private var owner: OverlayOwner? = null
    private var shownKey: String? = null

    private data class Content(val kind: LockScreenKind, val heading: String, val message: String, val footnote: String)
    private val content = mutableStateOf(Content(LockScreenKind.REMOTE, "", "", ""))

    /** Package of the phone app; while it is in front, the lock steps aside so the child can call. */
    val dialerPackage: String? by lazy {
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
    }

    val isShowing: Boolean get() = root != null

    fun canDraw(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    enum class Mode { PHONE_LOCK, APP_BLOCK }

    /** Shows or updates the overlay. Returns false if Android does not allow it (permission missing). */
    fun show(
        mode: Mode, heading: String, message: String, untilMillis: Long?, childName: String?,
        lockKind: LockKind? = null,
        webOff: Boolean = false
    ): Boolean {
        if (!canDraw()) return false
        val key = "$mode|$lockKind|$webOff|$heading|$message|$untilMillis"
        if (root != null && key == shownKey) return true
        return try {
            val kind = when {
                mode == Mode.APP_BLOCK -> LockScreenKind.APP_BLOCK
                webOff -> LockScreenKind.WEB_OFF
                lockKind == LockKind.BEDTIME -> LockScreenKind.BEDTIME
                lockKind == LockKind.LIMIT -> LockScreenKind.LIMIT
                else -> LockScreenKind.REMOTE
            }
            val footnote = when {
                mode == Mode.APP_BLOCK -> childName?.let { "K-Guard · $it" } ?: "K-Guard"
                webOff -> "Emergency calls still work."
                untilMillis != null -> "Unlocks at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(untilMillis))
                else -> "Ask your parent to unlock it."
            }
            content.value = Content(kind, heading, message, footnote)
            if (root == null) attach()
            shownKey = key
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not show lock overlay", e)
            hide()
            false
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        owner?.destroy()
        root = null; owner = null; shownKey = null
    }

    fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
    }

    private fun openDialer() {
        hide() // the service keeps the lock off only while the phone app is in front
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(dial) }
    }

    /** Steps aside for Android's VPN prompt (the engine keeps the lock off while it is in front). */
    private fun turnWebBackOn() {
        stepAsideUntil = System.currentTimeMillis() + STEP_ASIDE_MS
        hide()
        val consent = Intent(context, com.tele.k_guard.ui.screens.VpnConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(consent) }.onFailure { Log.e(TAG, "Could not open the VPN prompt", it) }
    }

    private fun checkPin(pin: String, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            val prefs = UserPreferencesRepository(context.applicationContext)
            when {
                !prefs.hasParentPin() -> onResult(false, "No parent PIN is set up on this phone.")
                prefs.verifyParentPin(pin) -> {
                    pinUnlockedUntil = System.currentTimeMillis() + PIN_UNLOCK_MS
                    onResult(true, null)
                    hide()
                }
                else -> onResult(false, "Incorrect PIN. Please try again.")
            }
        }
    }

    private fun attach() {
        val lifecycleOwner = OverlayOwner().also { it.start() }
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val c = content.value
                KGuardTheme {
                    LockScreenContent(
                        kind = c.kind,
                        heading = c.heading,
                        message = c.message,
                        footnote = c.footnote,
                        onEmergencyCall = ::openDialer,
                        onDismissAppBlock = { goHome(); hide() },
                        onTurnWebBackOn = ::turnWebBackOn,
                        onParentPinUnlock = ::checkPin
                    )
                }
            }
        }
        // Swallow the back key; every touch lands on the lock screen.
        val frame = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                if (event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)
        }.apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(frame, params)
        frame.requestFocus()
        root = frame
        owner = lifecycleOwner
    }

    /** Minimal lifecycle for a Compose view living in a service-owned window. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = savedState.savedStateRegistry
        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    companion object {
        private const val TAG = "KGuardLock"
        /** How long a correct parent PIN unlocks the phone on the spot. */
        const val PIN_UNLOCK_MS = 30 * 60_000L

        private const val STEP_ASIDE_MS = 15_000L

        /** Set when the child taps "Turn web protection back on", so the VPN prompt isn't covered. */
        @Volatile
        var stepAsideUntil: Long = 0L
            private set

        /** Set by a correct parent PIN; the engine leaves the phone unlocked until then. */
        @Volatile
        var pinUnlockedUntil: Long = 0L
            private set
    }
}

package com.kguard.app.live

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * What the child sees when K-Guard Live catches something: the screen itself, heavily blurred, with a friendly
 * card on top that explains what happened in words that fit the category, the AI's own message for the child,
 * and one practical tip. Harmful screens can only be left; milder ones can be revealed if the parent allows it.
 */
class LiveShieldOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    val isShowing: Boolean get() = root != null

    /** Look and words for each of K-Guard's categories. */
    private data class Style(val emoji: String, val title: String, val tip: String, val accent: Int)

    private fun style(category: String, harmful: Boolean): Style = when (category) {
        "cyberbullying" -> Style("💬", "Those words aren't okay",
            "You don't have to read mean messages. Block or mute the sender, keep a screenshot, and tell an adult you trust.", 0xFF6366F1.toInt())
        "threat" -> Style("🛑", "This could be unsafe",
            "If someone is threatening you or someone else, tell a trusted adult right away. You won't be in trouble.", 0xFFDC2626.toInt())
        "violence" -> Style("🙈", "Let's skip this one",
            "Some videos and pictures are too upsetting. If something you saw is stuck in your head, talking about it helps.", 0xFFEA580C.toInt())
        "sexual" -> Style("🙈", "This isn't meant for you",
            "If something like this popped up by itself, that's not your fault. You can tell your parent what happened.", 0xFFDB2777.toInt())
        "self_harm" -> Style("💙", "You matter",
            "If you're feeling sad or want to hurt yourself, please talk to a parent or another adult you trust right now.", 0xFF0EA5E9.toInt())
        "hate" -> Style("✋", "Everyone deserves respect",
            "Content that attacks people for who they are isn't okay. You can report it and talk about it with your parent.", 0xFF7C3AED.toInt())
        "drugs" -> Style("🚫", "Not for you",
            "Drugs, alcohol and vaping can hurt growing bodies. Ask a trusted adult if you're curious about something you saw.", 0xFF059669.toInt())
        "personal_info" -> Style("🔒", "Keep your details private",
            "Never share photos, your address, school or passwords with people online, and never agree to meet someone you met online.", 0xFFD97706.toInt())
        "scam" -> Style("🎣", "This looks like a trick",
            "Prizes and messages asking for passwords or codes are often scams. Don't tap links or share codes; ask your parent.", 0xFF0891B2.toInt())
        "profanity" -> Style("🤐", "Strong language ahead",
            "This has words that aren't right for your age. There's plenty of great stuff to watch instead!", 0xFF64748B.toInt())
        else -> Style("🛡️", if (harmful) "K-Guard covered this" else "Heads up",
            "If something online worries you or makes you feel bad, you can always talk to your parent.", 0xFF4F46E5.toInt())
    }

    /**
     * Covers the screen with [snapshot] blurred.
     * @param canReveal show "Continue anyway" (only for milder content, when the parent allows it).
     */
    @SuppressLint("SetTextI18n")
    fun show(
        snapshot: Bitmap?,
        category: String,
        harmful: Boolean,
        aiMessage: String,
        childName: String?,
        canReveal: Boolean,
        onGoBack: () -> Unit,
        onReveal: () -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return false
        hide()
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
        fun dpi(v: Int) = dp(v.toFloat()).toInt()
        val s = style(category, harmful)

        // Layer 1: the screen itself, blurred so it can't be made out.
        val blurred = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (snapshot != null) setImageBitmap(blur(snapshot)) else setBackgroundColor(0xFF1F2937.toInt())
        }
        // Layer 2: a soft dark wash, tinted with the category colour.
        val wash = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(withAlpha(s.accent, 0.35f), 0x99000000.toInt(), 0xCC000000.toInt()))
        }

        // Layer 3: the card.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24), dpi(28), dpi(24), dpi(20))
            background = GradientDrawable().apply { cornerRadius = dp(28f); setColor(0xF7FFFFFF.toInt()) }
            elevation = dp(12f)
        }
        val badge = TextView(context).apply {
            text = s.emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(s.accent, 0.14f)) }
            layoutParams = LinearLayout.LayoutParams(dpi(76), dpi(76))
        }
        val first = childName?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        val title = TextView(context).apply {
            text = s.title
            setTextColor(0xFF0F172A.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dpi(16), 0, dpi(8))
        }
        val message = TextView(context).apply {
            text = if (first != null && !aiMessage.contains(first)) "$first, ${aiMessage.replaceFirstChar { it.lowercase() }}" else aiMessage
            setTextColor(0xFF334155.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER; setLineSpacing(dp(2f), 1f)
        }
        val tip = TextView(context).apply {
            text = "💡  ${s.tip}"
            setTextColor(0xFF475569.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(2f), 1f)
            setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
            background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(withAlpha(s.accent, 0.08f)) }
        }
        val goBack = pill("Go back", filled = true, accent = s.accent) { onGoBack(); dismissAnimated() }
        val extra: View? = when {
            canReveal -> pill("Continue anyway", filled = false, accent = s.accent) { onReveal(); dismissAnimated() }
            category == "self_harm" -> pill("Call for help", filled = false, accent = s.accent) {
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                dismissAnimated()
            }
            else -> null
        }
        val footer = TextView(context).apply {
            text = "🛡️  K-Guard Live · your parent knows"
            setTextColor(0xFF94A3B8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER; setPadding(0, dpi(14), 0, 0)
        }

        card.addView(badge)
        card.addView(title)
        card.addView(message)
        card.addView(tip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(18) })
        card.addView(goBack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(52)).apply { topMargin = dpi(20) })
        extra?.let { card.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(8) }) }
        card.addView(footer)

        // Back acts like "Go back"; everything else underneath is unreachable.
        val frame = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) { onGoBack(); dismissAnimated() }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
            addView(blurred, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(wash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = dpi(24); rightMargin = dpi(24)
            })
        }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        )
        return try {
            wm.addView(frame, params)
            frame.requestFocus()
            root = frame
            // Fade the blur in, then pop the card.
            frame.alpha = 0f
            frame.animate().alpha(1f).setDuration(220).start()
            card.scaleX = 0.88f; card.scaleY = 0.88f; card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120).setDuration(320)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
            true
        } catch (e: Exception) {
            Log.e("KGuardLive", "Could not show the blur shield", e)
            hide()
            false
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun dismissAnimated() {
        val v = root ?: return
        v.animate().alpha(0f).setDuration(180).withEndAction { if (root === v) hide() }.start()
    }

    fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
    }

    private fun pill(label: String, filled: Boolean, accent: Int, onClick: () -> Unit): Button {
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setTextColor(if (filled) Color.WHITE else accent)
            background = GradientDrawable().apply {
                cornerRadius = dp(26f)
                if (filled) setColor(accent) else { setColor(Color.TRANSPARENT); setStroke(dp(1.5f).toInt(), withAlpha(accent, 0.5f)) }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    companion object {
        /**
         * Heavy frosted-glass blur that works on every Android version: shrink the frame a lot, run three box-blur
         * passes, and let the ImageView scale it back up smoothly.
         */
        fun blur(src: Bitmap): Bitmap {
            val w = max(1, src.width / 10)
            val h = max(1, src.height / 10)
            val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, radius = 2) }
            small.setPixels(px, 0, w, 0, 0, w, h)
            return small
        }

        private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
            val tmp = IntArray(px.size)
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var rs = 0; var gs = 0; var bs = 0; var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val c = if (horizontal) src[line * w + j] else src[j * w + line]
                        rs += c shr 16 and 0xFF; gs += c shr 8 and 0xFF; bs += c and 0xFF; n++
                    }
                    val idx = if (horizontal) line * w + i else i * w + line
                    dst[idx] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                }
            }
        }
    }
}

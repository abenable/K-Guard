package com.kguard.app.live

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils

/**
 * The only visible part of K-Guard Live: a soft glow along the left and right edges of the screen, drawn over
 * whatever the child is using. It never takes touches or focus, so the app underneath works normally; it is
 * there so the child always knows the AI can see the screen.
 */
class EdgeGlowOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: GlowView? = null

    /** Edge width in pixels; the screen analysis crops this much off each side so the glow is never judged. */
    val edgePx: Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22f, context.resources.displayMetrics).toInt()

    fun show(): Boolean {
        if (view != null) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return false
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        return try {
            val v = GlowView(context, edgePx)
            wm.addView(v, params)
            view = v
            true
        } catch (e: Exception) {
            Log.e("KGuardLive", "Could not show the edge glow", e)
            false
        }
    }

    /** Amber while something was just flagged, otherwise the calm blue/violet. */
    fun setAlert(alert: Boolean) { view?.alert = alert }

    fun hide() {
        view?.let { it.stop(); runCatching { wm.removeView(it) } }
        view = null
    }

    private class GlowView(context: Context, private val edge: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        var alert = false
            set(v) { field = v; invalidate() }

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
        fun stop() = animator.cancel()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val top = if (alert) AMBER else ColorUtils.blendARGB(BLUE, VIOLET, phase)
            val bottom = if (alert) ColorUtils.blendARGB(AMBER, RED, phase) else ColorUtils.blendARGB(TEAL, BLUE, phase)
            val strength = 0.55f + 0.45f * phase
            drawEdge(canvas, 0f, edge.toFloat(), h, top, bottom, strength, leftSide = true)
            drawEdge(canvas, w - edge, w, h, bottom, top, strength, leftSide = false)
        }

        private fun drawEdge(c: Canvas, x0: Float, x1: Float, h: Float, top: Int, bottom: Int, strength: Float, leftSide: Boolean) {
            // Colour runs top to bottom; brightness fades away from the screen edge.
            val outer = if (leftSide) x0 else x1
            val inner = if (leftSide) x1 else x0
            val mid = ColorUtils.blendARGB(top, bottom, 0.5f)
            paint.shader = LinearGradient(outer, 0f, inner, 0f,
                intArrayOf(withAlpha(mid, 0.85f * strength), withAlpha(mid, 0.30f * strength), 0),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(x0, 0f, x1, h, paint)
            // The bright core line along the very edge carries the top-to-bottom colour.
            val lineW = edge * 0.18f
            val lx0 = if (leftSide) x0 else x1 - lineW
            paint.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(withAlpha(top, strength), withAlpha(bottom, strength), withAlpha(top, strength)), null, Shader.TileMode.CLAMP)
            c.drawRect(lx0, 0f, lx0 + lineW, h, paint)
        }

        private fun withAlpha(color: Int, a: Float) = ColorUtils.setAlphaComponent(color, (a.coerceIn(0f, 1f) * 255).toInt())

        companion object {
            const val BLUE = 0xFF3B82F6.toInt()
            const val VIOLET = 0xFF8B5CF6.toInt()
            const val TEAL = 0xFF14B8A6.toInt()
            const val AMBER = 0xFFF59E0B.toInt()
            const val RED = 0xFFEF4444.toInt()
        }
    }
}

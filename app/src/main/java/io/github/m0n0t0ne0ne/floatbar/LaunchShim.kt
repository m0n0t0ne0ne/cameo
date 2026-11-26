package io.github.m0n0t0ne0ne.floatbar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator

/**
 * Opening animation for apps launched from the bar.
 *
 * Android can't animate another app's window for us, and apps started from outside Nothing's
 * launcher just pop into their window. So for a moment we draw a card in our own overlay: it grows
 * from the tapped icon to where Nothing places the pop-up, while the app starts underneath, then
 * fades away to reveal the real window.
 */
class LaunchShim(
    private val context: Context,
    private val windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    /**
     * The colour the app paints first (its splash-screen or window background), so the card hands
     * over to the real window without a colour jump.
     */
    fun launchColour(c: android.content.ComponentName): Int {
        val fallback = Color.parseColor("#151718")
        return try {
            val ai = context.packageManager.getActivityInfo(c, android.content.pm.PackageManager.ComponentInfoFlags.of(0L))
            val themeRes = if (ai.theme != 0) ai.theme else ai.applicationInfo.theme
            val pc = context.createPackageContext(c.packageName, 0)
            val theme = pc.resources.newTheme().apply { applyStyle(themeRes, true) }
            val a = theme.obtainStyledAttributes(
                intArrayOf(android.R.attr.windowSplashScreenBackground, android.R.attr.windowBackground)
            )
            val splash = a.getColor(0, 0)
            val bgDrawable = try { a.getDrawable(1) } catch (_: Exception) { null }
            a.recycle()
            when {
                splash != 0 && Color.alpha(splash) == 255 -> splash
                bgDrawable is android.graphics.drawable.ColorDrawable && Color.alpha(bgDrawable.color) == 255 -> bgDrawable.color
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * [ready] is polled after the card has grown; the card fades out once it returns true (the app
     * has drawn) or after a cap.
     */
    fun play(fromOnScreen: Rect, icon: Bitmap?, colour: Int, snapshot: Bitmap? = null, ready: () -> Boolean) {
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        // Where Nothing puts a new pop-up in portrait (measured on the phone: the app is drawn at
        // 74.9% scale, horizontally centred, its top at 22.8% of the screen height).
        val target = if (h >= w) {
            val pw = w * 0.749f
            // The pop-up's app area is 76.9% of the screen height (2154 of 2800 px), also scaled.
            val ph = h * 0.7693f * 0.749f
            val left = (w - pw) / 2f
            val top = h * 0.2282f
            RectF(left, top, left + pw, top + ph)
        } else {
            val pw = w * 0.5f
            val ph = h * 0.8f
            RectF((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f)
        }

        // Dark-themed context so the system's colour inversion leaves the card's colours alone.
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_Cameo)
        val view = ShimView(themed, RectF(fromOnScreen), target, icon, density, colour, snapshot)
        val lp = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            windowType,
            // Touchable on purpose: Android caps pass-through (not-touchable) overlays at 80% opacity,
            // which made the card see-through. It only lives for under a second.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            windowAnimations = 0
            title = "CameoLaunch"
            // Cover the whole display, including behind the status and navigation bars.
            fitInsetsTypes = 0
            fitInsetsSides = 0
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
        }
        try {
            wm.addView(view, lp)
        } catch (_: Exception) {
            return
        }

        // Grow (decelerating like Nothing's own animations), hold solid until the app has drawn,
        // then dissolve into it.
        val grow = GROW_MS
        val minHold = 120L
        val maxHold = 800L
        val fade = 180L
        val start = android.os.SystemClock.uptimeMillis()
        var fadeStart = -1L
        var lastPoll = 0L
        var isReady = false
        val tick = object : Runnable {
            override fun run() {
                val t = android.os.SystemClock.uptimeMillis() - start
                view.progress = GROW_CURVE.getInterpolation((t / grow.toFloat()).coerceIn(0f, 1f))
                if (fadeStart < 0 && t >= grow + minHold) {
                    if (!isReady && t - lastPoll >= 60) {
                        lastPoll = t
                        isReady = try { ready() } catch (_: Exception) { true }
                    }
                    // Give the app one more frame after it reports visible.
                    if (isReady || t >= grow + maxHold) fadeStart = t + if (isReady) 60 else 0
                }
                view.fade = if (fadeStart < 0) 0f else ((t - fadeStart) / fade.toFloat()).coerceIn(0f, 1f)
                view.invalidate()
                if (fadeStart >= 0 && t >= fadeStart + fade) {
                    try { wm.removeView(view) } catch (_: Exception) {}
                } else {
                    view.postOnAnimation(this)
                }
            }
        }
        view.postOnAnimation(tick)
    }

    private class ShimView(
        context: Context,
        private val from: RectF,
        private val to: RectF,
        private val icon: Bitmap?,
        private val d: Float,
        private val base: Int,
        private val snapshot: Bitmap?,
    ) : View(context) {
        var progress = 0f
        var fade = 0f

        private val card = Paint(Paint.ANTI_ALIAS_FLAG)
        private val snap = Paint(Paint.FILTER_BITMAP_FLAG)
        private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val r = RectF()
        private val loc = IntArray(2)

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        override fun onDraw(canvas: Canvas) {
            // Our window may not start exactly at the screen origin; draw in screen coordinates.
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val p = progress
            r.set(lerp(from.left, to.left, p), lerp(from.top, to.top, p), lerp(from.right, to.right, p), lerp(from.bottom, to.bottom, p))
            val radius = lerp(from.width() / 2f, 24f * d, p)
            val alpha = ((1f - fade) * 255).toInt()
            // Experimental: a still of the screen from just before the launch sits behind the card
            // and cross-fades with it, so the launcher's wallpaper zoom blends in instead of jumping.
            if (snapshot != null) {
                snap.alpha = alpha
                // Leave out the pop-up's spot once the card starts dissolving, so the old home
                // screen never shows through the app.
                canvas.save()
                if (fade > 0f) canvas.clipOutRect(to)
                canvas.drawBitmap(snapshot, 0f, 0f, snap)
                canvas.restore()
            }
            card.color = base
            card.alpha = alpha
            canvas.drawRoundRect(r, radius, radius, card)
            if (icon != null) {
                // Like a splash screen: icon centred; it eases down a touch as the card dissolves.
                val size = lerp(from.width(), 72f * d, p) * (1f - 0.15f * fade)
                val cx = r.centerX()
                val cy = r.centerY()
                bmp.alpha = alpha
                canvas.drawBitmap(icon, null, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), bmp)
            }
        }
    }

    companion object {
        private val GROW_CURVE = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** How long the card takes to grow. The app is started once it's almost full size. */
        const val GROW_MS = 260L
        const val LAUNCH_DELAY_MS = 230L
    }
}

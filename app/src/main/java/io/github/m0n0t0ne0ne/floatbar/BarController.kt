package io.github.m0n0t0ne0ne.floatbar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The edge bar itself: its window, layout, touch handling and launch animation. Hosted either by
 * [BarAccessibilityService] (accessibility overlay, no notifications) or by [BarService]
 * (app overlay + foreground service, the fallback).
 */
class BarController(private val ctx: Context, private val windowType: Int) {

    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private lateinit var bar: BarView
    private lateinit var params: WindowManager.LayoutParams
    var attached = false
        private set

    private var expanded = false
    private var animator: ValueAnimator? = null
    private var apps = emptyList<android.content.ComponentName>()

    // Drag state
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWinX = 0
    private var downWinY = 0
    private var moved = false

    /** Re-read the chosen apps and style; optionally open the dock. */
    fun refresh(expandAfter: Boolean) {
        if (!attached) return
        loadApps()
        if (expandAfter) expand() else relayout()
    }

    /** Hide the bar (e.g. on the lock screen) without tearing the window down. */
    fun setHidden(hidden: Boolean) {
        if (!attached) return
        val v = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
        if (bar.visibility == v) return
        if (hidden) collapseInstantly()
        bar.visibility = v
    }

    fun onConfigurationChanged() {
        if (attached) relayout()
    }

    fun destroy() {
        animator?.cancel()
        snapWorker.shutdown()
        if (attached) {
            try { wm.removeView(bar) } catch (_: Exception) {}
            attached = false
        }
    }

    // ------------------------------------------------------------------ apps

    private fun loadApps() {
        apps = prefs.apps.filter { Apps.exists(ctx, it) }
        val size = bar.dp(BarView.ICON).roundToInt()
        bar.style = BarStyle.from(ctx, prefs)
        bar.apps = apps.map { Apps.roundIcon(ctx, it, size, prefs.iconStyle) }
        Shiz.setManagedPackages(apps.map { it.packageName })
        refreshRunning()
    }

    private fun refreshRunning() {
        // No running indicators: an app is either open as a pop-up or closed.
        bar.running = emptyList()
    }

    // ------------------------------------------------------------- geometry

    private data class Screen(val w: Int, val h: Int, val top: Int, val bottom: Int)

    private fun screen(): Screen {
        val m = wm.currentWindowMetrics
        val insets = m.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return Screen(m.bounds.width(), m.bounds.height(), insets.top, insets.bottom)
    }

    private fun collapsedTop(s: Screen): Float {
        val cy = prefs.centerYFraction * s.h
        return (cy - bar.collapsedH / 2f).coerceIn(s.top.toFloat(), s.h - s.bottom - bar.collapsedH)
    }

    /**
     * The dock opens from the sliver's top edge and grows downward, so nothing appears to jump.
     * It only starts higher when there isn't room below.
     */
    private fun expandedTop(s: Screen): Float {
        bar.maxExpandedH = (s.h - s.bottom - s.top).toFloat()
        val eh = bar.shownExpandedH(apps.size)
        val max = (s.h - s.bottom - eh).coerceAtLeast(s.top.toFloat())
        return collapsedTop(s).coerceIn(s.top.toFloat(), max)
    }

    /** Small window that only covers the sliver (plus shadow room). */
    private fun layoutCollapsed() {
        val s = screen()
        val w = (bar.collapsedW + bar.pad).roundToInt()
        val h = (bar.collapsedH + 2 * bar.pad).roundToInt()
        val top = collapsedTop(s)
        params.width = w
        params.height = h
        params.x = if (prefs.onLeft) 0 else s.w - w
        params.y = (top - bar.pad).roundToInt()
        bar.onLeft = prefs.onLeft
        bar.collapsedTop = bar.pad
        bar.expandedTop = bar.pad
        update()
    }

    /** Window big enough for both the sliver and the open dock, so the animation can run inside it. */
    private fun layoutOpen() {
        val s = screen()
        val cTop = collapsedTop(s)
        val eTop = expandedTop(s)
        val winTop = minOf(cTop, eTop) - bar.pad
        val winBottom = maxOf(cTop + bar.collapsedH, eTop + bar.shownExpandedH(apps.size)) + bar.pad
        val w = (bar.expandedW + bar.pad).roundToInt()
        params.width = w
        params.height = (winBottom - winTop).roundToInt()
        params.x = if (prefs.onLeft) 0 else s.w - w
        params.y = winTop.roundToInt()
        bar.onLeft = prefs.onLeft
        bar.collapsedTop = cTop - winTop
        bar.expandedTop = eTop - winTop
        update()
    }

    /**
     * The system animates an overlay window sliding to its new position whenever its position
     * changes, which made the bar drift up and back. Turn that off.
     */
    private fun disableMoveAnimation(lp: WindowManager.LayoutParams) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            lp.setCanPlayMoveAnimation(false)
            return
        }
        try {
            val f = WindowManager.LayoutParams::class.java.getField("privateFlags")
            f.setInt(lp, f.getInt(lp) or 0x00000040) // PRIVATE_FLAG_NO_MOVE_ANIMATION
        } catch (_: Throwable) {
        }
    }

    private fun relayout() {
        if (expanded) layoutOpen() else layoutCollapsed()
        bar.invalidate()
    }

    private fun update() {
        if (attached) wm.updateViewLayout(bar, params)
    }

    // ----------------------------------------------------------- open/close

    private fun expand() {
        if (expanded) return
        expanded = true
        expandedAt = SystemClock.uptimeMillis()
        refreshRunning()
        layoutOpen()
        bar.scroll = 0f
        animate(1f)
    }

    private fun collapseInstantly() {
        animator?.cancel()
        expanded = false
        bar.progress = 0f
        layoutCollapsed()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        animate(0f) { layoutCollapsed() }
    }

    private fun animate(target: Float, end: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(bar.progress, target).apply {
            duration = 320
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { bar.progress = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) end?.invoke()
                }
            })
            start()
        }
    }

    // --------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val slop = bar.dp(4f)
        bar.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // Ignore stray outside events that arrive while the dock is still opening.
                if (SystemClock.uptimeMillis() - expandedAt > 400) collapse()
                return@setOnTouchListener true
            }
            if (expanded) {
                handleDockTouch(e)
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animator?.cancel()
                    downRawX = e.rawX; downRawY = e.rawY
                    downWinX = params.x; downWinY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        startDrag()
                    }
                    if (moved) {
                        params.x = (dragStartX + dx).roundToInt()
                        params.y = (downWinY + dy).roundToInt()
                        update()
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) endDrag() else expand()
                MotionEvent.ACTION_CANCEL -> if (moved) endDrag()
            }
            true
        }
    }

    // ---- Dock taps and long-presses ----
    private var expandedAt = 0L
    private var lastLaunchAt = 0L
    private var pressHit: BarView.Hit? = null
    private var longPressed = false
    private val longPress = Runnable {
        val hit = pressHit as? BarView.Hit.App ?: return@Runnable
        longPressed = true
        if (prefs.longPressOpensOtherWay) {
            bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            launchApp(hit.index, !prefs.openAsPopUp)
        } else {
            closeApp(hit.index)
        }
    }

    // Scrolling the icon column (only when the dock is taller than the screen).
    private var dockDownY = 0f
    private var dockScrollStart = 0f
    private var dockScrolling = false

    private fun handleDockTouch(e: MotionEvent) {
        if (bar.maxScroll > 0f) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockDownY = e.y; dockScrollStart = bar.scroll; dockScrolling = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - dockDownY
                    if (!dockScrolling && abs(dy) > ViewConfiguration.get(ctx).scaledTouchSlop) {
                        dockScrolling = true
                        bar.removeCallbacks(longPress)
                        pressHit = null
                    }
                    if (dockScrolling) {
                        bar.scroll = dockScrollStart - dy
                        return
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dockScrolling) {
                    dockScrolling = false
                    pressHit = null
                    return
                }
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                // Accept presses once the dock is mostly open; hit-test against its final layout.
                pressHit = if (bar.progress >= 0.6f) bar.hitTest(e.x, e.y) else null
                if (pressHit is BarView.Hit.App) {
                    bar.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressHit != null && bar.hitTest(e.x, e.y) != pressHit) {
                    bar.removeCallbacks(longPress)
                    pressHit = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                bar.removeCallbacks(longPress)
                pressHit = null
            }
            MotionEvent.ACTION_UP -> {
                bar.removeCallbacks(longPress)
                val hit = pressHit
                pressHit = null
                if (longPressed || hit == null) return
                when (hit) {
                    BarView.Hit.Outside -> collapse()
                    BarView.Hit.Empty -> Unit
                    BarView.Hit.Settings -> {
                        collapse()
                        ctx.startActivity(
                            Intent(ctx, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    }
                    is BarView.Hit.App -> launchApp(hit.index, prefs.openAsPopUp)
                }
            }
        }
    }

    /** Open the dock app at [index], as a pop-up (with the grow-from-icon card) or full screen. */
    private fun launchApp(index: Int, asPopUp: Boolean) {
        // One tap = one launch, even if the touch is delivered twice.
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunchAt < 700) return
        lastLaunchAt = now
        val app = apps.getOrNull(index) ?: return
        // Get the dock out of the way at once so its closing animation doesn't
        // play on top of the app's opening animation.
        // Opening animation: a card grows from this icon to the pop-up's spot.
        if (asPopUp) {
            val icon = bar.iconRect(index)
            val loc = IntArray(2).also { bar.getLocationOnScreen(it) }
            icon.offset(loc[0], loc[1])
            val pkg = app.packageName
            val glyph = bar.apps.getOrNull(index)
            val colour = shim.launchColour(app)
            val ready = { Shiz.tasks()?.any { it.pkg == pkg && it.visible } ?: false }
            collapseInstantly()
            if (prefs.smoothBackground && Shiz.service != null) {
                // Snapshot first (about 0.1 s, after the bar has redrawn collapsed).
                bar.postOnAnimation {
                    snapWorker.execute {
                        val still = Shiz.captureScreen()
                        bar.post {
                            shim.play(icon, glyph, colour, still, ready)
                            bar.postDelayed({ Apps.launch(ctx, app, true, index) }, LaunchShim.LAUNCH_DELAY_MS)
                        }
                    }
                }
            } else {
                shim.play(icon, glyph, colour, null, ready)
                // Start the app when the card has (almost) finished growing, so the
                // pop-up never peeks out from behind a card that is still small.
                bar.postDelayed({ Apps.launch(ctx, app, true, index) }, LaunchShim.LAUNCH_DELAY_MS)
            }
        } else {
            collapseInstantly()
            Apps.launch(ctx, app, false, index)
        }
    }

    /** Long-press: close the app for real (background process) and clear its red dot. */
    private fun closeApp(index: Int) {
        val app = apps.getOrNull(index) ?: return
        bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Apps.close(ctx, app.packageName)
        bar.animateClosing(index) { refreshRunning() }
    }

    private val shim by lazy { LaunchShim(ctx, windowType) }
    private val snapWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var dragStartX = 0f

    private fun startDrag() {
        // While dragging the sliver floats free: shadow room on both sides, rounded all round.
        val w = (bar.collapsedW + 2 * bar.pad).roundToInt()
        dragStartX = if (prefs.onLeft) downWinX.toFloat() else downWinX - bar.pad
        params.width = w
        bar.dragging = true
        update()
    }

    private fun endDrag() {
        val s = screen()
        val centerX = params.x + params.width / 2f
        val left = centerX < s.w / 2f
        val sliverTop = params.y + bar.pad
        prefs.onLeft = left
        prefs.centerYFraction = (sliverTop + bar.collapsedH / 2f) / s.h

        val finalW = (bar.collapsedW + bar.pad).roundToInt()
        val targetX = if (left) 0 else s.w - finalW
        // Keep the visible sliver still while switching to the edge-docked window shape.
        val fromX = params.x + if (left) bar.pad.roundToInt() else 0
        bar.dragging = false
        bar.onLeft = left
        params.width = finalW
        params.x = fromX
        params.y = (collapsedTop(s) - bar.pad).roundToInt()
        update()

        animator?.cancel()
        animator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                update()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { layoutCollapsed() }
            })
            start()
        }
    }

    // Last, so every property above is initialised before the window goes up.
    init {
        prefs.migrateGraphiteOpacity()
        Shiz.init(ctx)
        Shiz.connectIfAllowed()
        // Use the app's dark theme for the overlay. With the service's default (light) theme the
        // system's "force invert" feature treated the bar as a light app and inverted its colours,
        // so a white bar came out near-black.
        bar = BarView(android.view.ContextThemeWrapper(ctx, R.style.Theme_Cameo))
        bar.onLeft = prefs.onLeft
        params = WindowManager.LayoutParams(
            1, 1,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Cameo"
            windowAnimations = 0
        }
        disableMoveAnimation(params)
        setupTouch()
        loadApps()
        layoutCollapsed()
        attached = try {
            wm.addView(bar, params)
            true
        } catch (_: Exception) {
            false
        }
    }
}

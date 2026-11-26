package io.github.m0n0t0ne0ne.floatbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Draws the bar in all its states. The service owns the window and the gestures; this view only
 * paints the current state and answers hit tests.
 *
 * Collapsed "sliver": 11 x 69dp, 5 dots (middle red), inner corners 5dp, edge side square.
 * Expanded "dock": 59dp wide, settings dot grid on top, then up to ten 44dp round app icons (the icon column scrolls if the screen is too short).
 */
class BarView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    fun dp(v: Float) = v * dp

    // ---- Geometry in dp (spec) ----
    val collapsedW get() = dp(11f)
    val collapsedH get() = dp(69f)
    val expandedW get() = dp(1f + 9f + ICON + 9f + 1f)
    fun expandedH(n: Int) = dp(2f + 14f + 23f + (if (n > 0) GRID_GAP + n * ICON + (n - 1) * ICON_GAP else 0f) + 14f)
    val pad get() = dp(6f) // room for the shadow on the non-edge sides

    /** Tallest the open dock may be on this screen; set by the service. */
    var maxExpandedH = Float.MAX_VALUE

    /** Height the open dock is actually drawn at: its full height, or the screen's if that is less. */
    fun shownExpandedH(n: Int) = minOf(expandedH(n), maxExpandedH)

    /** How far the icon column can scroll when the dock doesn't fit (0 when it does). */
    val maxScroll get() = (expandedH(apps.size) - shownExpandedH(apps.size)).coerceAtLeast(0f)

    /** Current scroll of the icon column, in px. */
    var scroll = 0f
        set(value) { field = value.coerceIn(0f, maxScroll); invalidate() }

    // ---- State, set by the service ----
    var onLeft = false
    var progress = 0f
        set(value) { field = value; invalidate() }
    var dragging = false
        set(value) { field = value; invalidate() }
    var collapsedTop = 0f
    var expandedTop = 0f
    var apps: List<Bitmap?> = emptyList()
        set(value) { field = value; invalidate() }
    var running: List<Boolean> = emptyList()
        set(value) { field = value; invalidate() }
    var style = BarStyle(
        fill = Color.parseColor("#B3000000"),
        dot = Color.parseColor("#E8E8E8"),
        accent = Color.parseColor("#D71921"),
        outline = Color.parseColor("#26FFFFFF"),
    )
        set(value) { field = value; invalidate() }

    // Long-press close feedback: the icon shrinks and fades, then comes back without its dot.
    private var closingIndex = -1
    private var closingT = 0f

    fun animateClosing(index: Int, end: () -> Unit) {
        closingIndex = index
        android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 360
            addUpdateListener { closingT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    closingIndex = -1; closingT = 0f; end(); invalidate()
                }
            })
            start()
        }
    }

    // ---- Paints ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        setShadowLayer(dp(3f), 0f, dp(1f), Color.parseColor("#59000000"))
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val red get() = style.accent
    private val white get() = style.dot
    private val runningRed = Color.parseColor("#D71921")

    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Current bar rectangle (without the hidden edge-side extension). */
    fun barRect(out: RectF): RectF {
        val p = progress
        val w = lerp(collapsedW, expandedW, p)
        val h = lerp(collapsedH, shownExpandedH(apps.size), p)
        val top = lerp(collapsedTop, expandedTop, p)
        val left = when {
            dragging -> pad
            onLeft -> 0f
            else -> width - w
        }
        out.set(left, top, left + w, top + h)
        return out
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress
        barRect(rect)

        // Corner radii: rounded on the screen-facing side, square on the edge side.
        val inner: Float
        val edge: Float
        if (dragging) {
            inner = dp(8f); edge = dp(8f)
        } else {
            inner = minOf(lerp(dp(5f), dp(ICON / 2f + 10f), p), rect.width() / 2f, rect.height() / 2f)
            edge = 0f
        }
        val (tl, tr, br, bl) = if (onLeft) listOf(edge, inner, inner, edge) else listOf(inner, edge, edge, inner)
        radii[0] = tl; radii[1] = tl; radii[2] = tr; radii[3] = tr
        radii[4] = br; radii[5] = br; radii[6] = bl; radii[7] = bl

        // Push the edge side just past the screen so its outline is hidden while collapsed;
        // when fully open the outline shows on all four sides.
        val ext = if (dragging) 0f else (1f - p) * dp(2f)
        val drawRect = RectF(rect)
        if (!dragging) {
            if (onLeft) drawRect.left -= ext else drawRect.right += ext
        }
        val half = stroke.strokeWidth / 2f
        drawRect.inset(half, half)

        path.reset()
        path.addRoundRect(drawRect, radii, Path.Direction.CW)

        fill.color = style.fill
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, fill)
        stroke.color = if (dragging) Color.parseColor("#777777") else style.outline
        canvas.drawPath(path, stroke)

        canvas.save()
        canvas.clipPath(path)
        val cx = rect.centerX()

        // Collapsed content: 5 dots, 3dp each, 6dp gaps, middle red.
        val collapsedAlpha = ((1f - p * 2.5f).coerceIn(0f, 1f) * 255).toInt()
        if (collapsedAlpha > 0) {
            val r = dp(1.5f)
            val step = dp(3f + 6f)
            val cy = rect.centerY()
            for (i in -2..2) {
                dot.color = if (i == 0) red else white
                dot.alpha = collapsedAlpha
                canvas.drawCircle(cx, cy + i * step, r, dot)
            }
        }

        // Expanded content, laid out at its final positions and revealed by the clip.
        val expandedAlpha = (((p - 0.4f) / 0.6f).coerceIn(0f, 1f) * 255).toInt()
        if (expandedAlpha > 0) {
            val gridTop = rect.top + dp(1f + 14f)
            val dotR = dp(2.5f)
            val cell = dp(5f + 4f)
            val gridCy = gridTop + dp(23f) / 2f
            for (row in -1..1) for (col in -1..1) {
                dot.color = if (row == 0 && col == 0) red else white
                dot.alpha = expandedAlpha
                canvas.drawCircle(cx + col * cell, gridCy + row * cell, dotR, dot)
            }
            if (maxScroll > 0f) {
                // Only when the dock is taller than the screen: the icons scroll under the grid.
                canvas.clipRect(rect.left, gridTop + dp(23f + GRID_GAP / 2f), rect.right, rect.bottom - dp(6f))
            }
            var y = gridTop + dp(23f + GRID_GAP) - scroll
            val size = dp(ICON)
            bmpPaint.alpha = expandedAlpha
            apps.forEachIndexed { i, bmp ->
                val left = cx - size / 2f
                val shrink = if (i == closingIndex) closingT * size * 0.18f else 0f
                bmpPaint.alpha = if (i == closingIndex) (expandedAlpha * (1f - 0.6f * closingT)).toInt() else expandedAlpha
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, RectF(left + shrink, y + shrink, left + size - shrink, y + size - shrink), bmpPaint)
                } else {
                    dot.color = Color.parseColor("#2A2A2A"); dot.alpha = expandedAlpha
                    canvas.drawCircle(cx, y + size / 2f, size / 2f, dot)
                }
                if (running.getOrElse(i) { false }) {
                    val rx = left + size - dp(4f)
                    val ry = y + dp(4f)
                    dot.color = Color.BLACK; dot.alpha = expandedAlpha
                    canvas.drawCircle(rx, ry, dp(6f), dot)
                    dot.color = runningRed; dot.alpha = expandedAlpha
                    canvas.drawCircle(rx, ry, dp(4f), dot)
                }
                y += size + dp(ICON_GAP)
            }
        }
        canvas.restore()
    }

    companion object {
        /** Space between the settings dot grid and the first icon, and between icons (dp). */
        const val GRID_GAP = 8f

        /** App icon circle size in the open dock (dp). */
        const val ICON = 44f
        const val ICON_GAP = 8f
    }

    /** Where app icon [index] is drawn in the fully open dock, in view coordinates. */
    fun iconRect(index: Int): android.graphics.Rect {
        val saved = progress
        progress = 1f
        barRect(rect)
        progress = saved
        val size = dp(ICON)
        val top = rect.top + dp(1f + 14f) + dp(23f + GRID_GAP) + index * (size + dp(ICON_GAP)) - scroll
        val left = rect.centerX() - size / 2f
        return android.graphics.Rect(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
    }

    /** Result of a tap on the open dock. */
    sealed interface Hit {
        object Outside : Hit
        object Empty : Hit
        object Settings : Hit
        data class App(val index: Int) : Hit
    }

    fun hitTest(x: Float, y: Float): Hit {
        barRect(rect)
        if (!rect.contains(x, y)) return Hit.Outside
        val gridTop = rect.top + dp(1f + 14f)
        // Generous targets: the full width of the dock.
        if (y >= gridTop - dp(8f) && y <= gridTop + dp(23f + 2f)) return Hit.Settings
        if (maxScroll > 0f && (y < gridTop + dp(23f + GRID_GAP / 2f) || y > rect.bottom - dp(6f))) return Hit.Empty
        var top = gridTop + dp(23f + GRID_GAP) - scroll
        for (i in apps.indices) {
            if (y >= top - dp(4f) && y <= top + dp(ICON + 4f)) return Hit.App(i)
            top += dp(ICON + ICON_GAP)
        }
        return Hit.Empty
    }
}

package io.github.m0n0t0ne0ne.floatbar

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Settings screen: pick up to ten apps, bar colour, icon style, how apps open. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val selected = mutableListOf<ComponentName>()
    private var all = emptyList<AppEntry>()
    private var shown = emptyList<AppEntry>()
    private var duplicateLabels = emptySet<String>()

    private lateinit var header: View
    private lateinit var counter: TextView
    private lateinit var segments: List<View>
    private lateinit var permissionRow: View
    private lateinit var showSwitch: Switch
    private lateinit var swatchRow: LinearLayout
    private lateinit var iconChips: LinearLayout
    private lateinit var openChips: LinearLayout
    private lateinit var longPressChips: LinearLayout
    private lateinit var adapter: AppAdapter
    private var opacitySeek: SeekBar? = null
    private lateinit var orderList: LinearLayout
    private lateinit var undoRow: LinearLayout
    private lateinit var undoText: TextView
    private var lastRemoved: Pair<Int, ComponentName>? = null
    private val hideUndo = Runnable { lastRemoved = null; undoRow.visibility = View.GONE }
    private lateinit var barModeStatus: TextView
    private lateinit var barModeRow: LinearLayout
    private lateinit var hideNote: View
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuRow: LinearLayout
    private val shizukuListener: () -> Unit = { runOnUiThread { if (::shizukuRow.isInitialized) renderShizuku() } }

    private val io = Executors.newSingleThreadExecutor()

    private val bg = Color.parseColor("#0B0B0B")
    private val fg = Color.parseColor("#EEEEEE")
    private val red = Color.parseColor("#D71921")
    private val muted = Color.parseColor("#999999")

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Shiz.init(this)
        Shiz.addListener(shizukuListener)
        Shiz.connectIfAllowed()
        selected.addAll(prefs.apps)
        setContentView(buildUi())
        loadApps()
        askNotificationsOnce()
    }

    override fun onResume() {
        super.onResume()
        val granted = Bar.canShow(this)
        permissionRow.visibility = if (granted) View.GONE else View.VISIBLE
        showSwitch.isChecked = prefs.barEnabled && granted
        if (granted && prefs.barEnabled) Bar.start(this)
        renderBarMode()
        Shiz.connectIfAllowed()
        if (::shizukuRow.isInitialized) renderShizuku()
    }

    override fun onDestroy() {
        Shiz.removeListener(shizukuListener)
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(dp(20f), bars.top + dp(16f), dp(20f), bars.bottom + dp(16f))
            insets
        }

        header = buildHeader()
        adapter = AppAdapter()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(pill("DONE", filled = true) { save() }, lp(top = 10f, height = 44f))
        updateCounter()
        return root
    }

    /** Everything above the app list; scrolls with the list. */
    private fun buildHeader(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(4f), 0, dp(6f))
        }

        h.addView(text("CAMEO", 22f, fg, Fonts.dot).apply { letterSpacing = 0.12f })

        permissionRow = pill("Allow \"Display over other apps\"", filled = false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        h.addView(permissionRow, lp(top = 14f, height = 44f))

        // Show bar on/off.
        val showRow = row()
        showRow.addView(text("Show bar", 15f, fg, Fonts.sans), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        showSwitch = Switch(this).apply {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.parseColor("#888888"))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(red, Color.parseColor("#333333"))
            )
            setOnCheckedChangeListener { _, on ->
                val granted = Bar.canShow(this@MainActivity)
                if (on == (prefs.barEnabled && granted)) return@setOnCheckedChangeListener
                prefs.barEnabled = on
                if (on) {
                    if (granted) Bar.start(this@MainActivity) else permissionRow.performClick()
                } else {
                    Bar.stop(this@MainActivity)
                }
            }
        }
        showRow.addView(showSwitch)
        h.addView(showRow, lp(top = 10f))

        // Bar mode: accessibility overlay (no notifications) or the old overlay + foreground service.
        h.addView(section("BAR MODE"), lp(top = 18f))
        barModeStatus = text("", 13f, muted, Fonts.sans)
        h.addView(barModeStatus, lp(top = 6f))
        barModeRow = row()
        addChip(barModeRow, chip("Accessibility settings", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        // Fallback mode only: a foreground service must post a notification; this lets the user
        // switch its channel off (the bar keeps running).
        hideNote = chip("Hide notification", false) {
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, BarService.CHANNEL))
        }
        addChip(barModeRow, hideNote)
        h.addView(barModeRow, lp(top = 10f))
        renderBarMode()

        // Bar colour.
        h.addView(section("BAR COLOUR"), lp(top = 18f))
        swatchRow = row()
        h.addView(swatchRow, lp(top = 10f))
        renderSwatches()

        val opacityRow = row()
        opacityRow.addView(text("Opacity", 13f, muted, Fonts.sans), LinearLayout.LayoutParams(dp(64f), ViewGroup.LayoutParams.WRAP_CONTENT))
        val opacityValue = text("", 13f, fg, Fonts.dot)
        val seek = SeekBar(this).also { opacitySeek = it }.apply {
            max = 60
            progress = ((prefs.barOpacity - 0.4f) * 100).roundToInt()
            progressTintList = ColorStateList.valueOf(red)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#333333"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    opacityValue.text = "${40 + p}%"
                    if (fromUser) { prefs.barOpacity = (40 + p) / 100f; applyToBar() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        opacityValue.text = "${40 + seek.progress}%"
        opacityRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        opacityRow.addView(opacityValue, LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        h.addView(opacityRow, lp(top = 8f))

        // Icons.
        h.addView(section("ICONS"), lp(top = 18f))
        iconChips = row()
        h.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(iconChips)
        }, lp(top = 10f))
        renderIconChips()

        // Open apps as.
        h.addView(section("OPEN APPS AS"), lp(top = 18f))
        openChips = row()
        h.addView(openChips, lp(top = 10f))
        renderOpenChips()

        h.addView(section("LONG PRESS"), lp(top = 18f))
        longPressChips = row()
        h.addView(longPressChips, lp(top = 10f))
        renderLongPressChips()

        // Shizuku (optional).
        h.addView(section("SHIZUKU"), lp(top = 18f))
        shizukuStatus = text("", 13f, muted, Fonts.sans)
        h.addView(shizukuStatus, lp(top = 6f))
        shizukuRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        h.addView(shizukuRow, lp(top = 10f))
        renderShizuku()

        // Bar order.
        h.addView(section("BAR ORDER"), lp(top = 22f))
        h.addView(text("Drag \u2261 to rearrange, or use the arrows. Swipe an app left to remove it. The top of the list is the top of the bar.", 12f, muted, Fonts.sans), lp(top = 6f))
        orderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        h.addView(orderList, lp(top = 8f))
        undoRow = row().apply {
            setPadding(dp(16f), 0, dp(6f), 0)
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor("#222222")) }
            visibility = View.GONE
        }
        undoText = text("", 14f, fg, Fonts.sans).apply { isSingleLine = true }
        undoRow.addView(undoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoRow.addView(text("UNDO", 14f, fg, Fonts.dot).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener { undoRemove() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        h.addView(undoRow, lp(top = 8f, height = 46f))

        // Apps.
        h.addView(section("APPS"), lp(top = 22f))
        val sub = row()
        sub.addView(text("Pick any installed app, up to ten.", 12f, muted, Fonts.sans), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        counter = text("", 13f, red, Fonts.dot)
        sub.addView(counter)
        h.addView(sub, lp(top = 6f))

        val segRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        segments = (0 until Prefs.MAX_APPS).map { i ->
            View(this).also { v ->
                segRow.addView(v, LinearLayout.LayoutParams(0, dp(5f), 1f).apply { if (i > 0) leftMargin = dp(6f) })
            }
        }
        h.addView(segRow, lp(top = 12f))

        val search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.sans
            isSingleLine = true
            setPadding(dp(18f), 0, dp(18f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(Color.parseColor("#151515"))
                setStroke(dp(1f), Color.parseColor("#333333"))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
        }
        h.addView(search, lp(top = 14f, height = 44f))
        return h
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun section(title: String) = text(title, 13f, muted, Fonts.dot).apply { letterSpacing = 0.12f }

    private fun text(s: String, sp: Float, color: Int, tf: Typeface) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = tf
    }

    private fun lp(top: Float = 0f, height: Float? = null) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        height?.let { dp(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            if (filled) setColor(Color.parseColor("#F3F3F3"))
            else { setColor(Color.TRANSPARENT); setStroke(dp(1f), red) }
        }
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (filled) 16f else 14f)
            typeface = if (filled) Fonts.dot else Fonts.sans
            letterSpacing = if (filled) 0.12f else 0f
            setTextColor(if (filled) Color.parseColor("#111111") else fg)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33888888")), shape, null)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** Small rounded choice chip. */
    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Fonts.sans
        setTextColor(if (on) Color.parseColor("#111111") else fg)
        setPadding(dp(14f), 0, dp(14f), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            if (on) setColor(Color.parseColor("#F3F3F3"))
            else { setColor(Color.parseColor("#151515")); setStroke(dp(1f), Color.parseColor("#333333")) }
        }
        setOnClickListener { onClick() }
    }

    private fun addChip(parent: LinearLayout, v: View) {
        val vertical = parent.orientation == LinearLayout.VERTICAL
        (v as? TextView)?.apply { isSingleLine = true; maxLines = 1 }
        parent.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34f)).apply {
            if (parent.childCount > 0) { if (vertical) topMargin = dp(8f) else leftMargin = dp(8f) }
        })
    }

    // ------------------------------------------------------------ bar order

    private val orderRowH get() = dp(52f)

    /** The chosen apps in bar order, with a drag handle and up/down buttons. */
    private fun renderOrder() {
        orderList.removeAllViews()
        if (selected.isEmpty()) {
            orderList.addView(text("No apps yet. Pick some below.", 13f, muted, Fonts.sans))
            return
        }
        selected.forEachIndexed { i, c ->
            val entry = all.firstOrNull { it.component == c }
            val rowV = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), 0, dp(4f), 0)
                background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor("#151515")) }
            }
            val handle = text("\u2261", 22f, muted, Fonts.sans).apply {
                gravity = Gravity.CENTER
                contentDescription = "Drag to reorder"
            }
            rowV.addView(handle, LinearLayout.LayoutParams(dp(40f), ViewGroup.LayoutParams.MATCH_PARENT))
            val icon = ImageView(this)
            rowV.addView(icon, LinearLayout.LayoutParams(dp(30f), dp(30f)))
            val style = prefs.iconStyle
            io.execute {
                val bmp = Apps.roundIcon(this, c, dp(30f), style)
                runOnUiThread { icon.setImageBitmap(bmp) }
            }
            rowV.addView(text(entry?.label ?: c.packageName, 15f, fg, Fonts.sans).apply { isSingleLine = true },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12f) })
            rowV.addView(arrow("\u25B2", "Move up", i > 0) { moveApp(i, i - 1) },
                LinearLayout.LayoutParams(dp(40f), ViewGroup.LayoutParams.MATCH_PARENT))
            rowV.addView(arrow("\u25BC", "Move down", i < selected.size - 1) { moveApp(i, i + 1) },
                LinearLayout.LayoutParams(dp(40f), ViewGroup.LayoutParams.MATCH_PARENT))
            // Behind the row: the red "remove" strip that a left swipe uncovers.
            val cell = FrameLayout(this)
            cell.addView(text("REMOVE", 13f, Color.WHITE, Fonts.dot).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                letterSpacing = 0.12f
                setPadding(0, 0, dp(20f), 0)
                background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(red) }
                alpha = 0f
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cell.addView(rowV, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            rowV.contentDescription = "${entry?.label ?: c.packageName}. Swipe left to remove."
            orderList.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, orderRowH - dp(6f)).apply { topMargin = dp(6f) })
            attachDrag(handle, cell, i)
            attachSwipe(rowV, cell.getChildAt(0), i)
        }
    }

    private fun arrow(glyph: String, desc: String, enabled: Boolean, onClick: () -> Unit) =
        text(glyph, 16f, if (enabled) fg else Color.parseColor("#444444"), Fonts.sans).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            isEnabled = enabled
            if (enabled) setOnClickListener { onClick() }
        }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View, rowV: View, index: Int) {
        var startY = 0f
        var target = index
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    startY = e.rawY
                    target = index
                    rowV.elevation = dp(6f).toFloat()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    rowV.translationY = dy
                    target = (index + Math.round(dy / orderRowH)).coerceIn(0, selected.size - 1)
                    // Slide the other rows out of the way.
                    for (k in 0 until orderList.childCount) {
                        if (k == index) continue
                        val other = orderList.getChildAt(k)
                        val shift = when {
                            index < target && k in (index + 1)..target -> -orderRowH.toFloat()
                            index > target && k in target until index -> orderRowH.toFloat()
                            else -> 0f
                        }
                        if (other.translationY != shift) other.animate().translationY(shift).setDuration(120).start()
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    val to = target
                    // Rebuilding the list from inside this touch event removed the very view the
                    // event was being delivered to, which crashed the app. Do it right after instead.
                    orderList.post {
                        for (k in 0 until orderList.childCount) {
                            orderList.getChildAt(k).animate().cancel()
                            orderList.getChildAt(k).translationY = 0f
                        }
                        moveApp(index, to)
                    }
                }
            }
            true
        }
    }

    /** Swipe a bar-order row left: past 40% of its width (or flung) it's removed, otherwise it springs back. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachSwipe(rowV: View, behind: View, index: Int) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var swiping = false
        var tracker: android.view.VelocityTracker? = null
        fun settle(remove: Boolean) {
            if (remove) {
                rowV.animate().translationX(-rowV.width.toFloat()).setDuration(160)
                    .withEndAction { orderList.post { removeApp(index) } }.start()
            } else {
                rowV.animate().translationX(0f).setDuration(160).start()
                behind.animate().alpha(0f).setDuration(160).start()
            }
        }
        rowV.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; swiping = false
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!swiping && dx < -slop && Math.abs(dx) > Math.abs(dy)) {
                        swiping = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        val tx = (dx + slop).coerceAtMost(0f)
                        v.translationX = tx
                        behind.alpha = (-tx / (v.width * 0.4f)).coerceIn(0f, 1f)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (swiping) {
                        tracker?.addMovement(e)
                        tracker?.computeCurrentVelocity(1000)
                        val vx = tracker?.xVelocity ?: 0f
                        settle(v.translationX < -v.width * 0.4f || vx < -dp(1200f))
                    }
                    tracker?.recycle(); tracker = null
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (swiping) settle(false)
                    tracker?.recycle(); tracker = null
                }
            }
            true
        }
    }

    private fun removeApp(index: Int) {
        if (index !in selected.indices) return
        val c = selected.removeAt(index)
        lastRemoved = index to c
        applyApps()
        val label = all.firstOrNull { it.component == c }?.label ?: c.packageName
        undoText.text = "$label removed"
        undoRow.visibility = View.VISIBLE
        undoRow.removeCallbacks(hideUndo)
        undoRow.postDelayed(hideUndo, 6000)
    }

    private fun undoRemove() {
        val (index, c) = lastRemoved ?: return
        undoRow.removeCallbacks(hideUndo)
        hideUndo.run()
        if (c !in selected && selected.size < Prefs.MAX_APPS) selected.add(index.coerceAtMost(selected.size), c)
        applyApps()
    }

    /** Save the chosen apps, refresh the bar, the order list, the counter and the checkmarks. */
    private fun applyApps() {
        prefs.apps = selected.toList()
        if (prefs.barEnabled) Bar.start(this)
        updateCounter()
        renderOrder()
        adapter.notifyItemRangeChanged(1, shown.size)
    }

    private fun moveApp(from: Int, to: Int) {
        if (from in selected.indices && to in selected.indices && from != to) {
            val c = selected.removeAt(from)
            selected.add(to, c)
            // Apply straight away so the bar on screen shows the new order.
            prefs.apps = selected.toList()
            if (prefs.barEnabled) Bar.start(this)
        }
        renderOrder()
    }

    private fun renderSwatches() {
        swatchRow.removeAllViews()
        val presets = listOf(
            Prefs.BAR_BLACK to "Black",
            Prefs.BAR_GRAPHITE to "Graphite",
            Prefs.BAR_WHITE to "White",
            Prefs.BAR_RED to "Red",
            Prefs.BAR_WALLPAPER to "Wallpaper",
        )
        presets.forEach { (key, name) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener {
                    prefs.barColorPreset = key
                    // Graphite is Nothing's solid folder colour; start it fully opaque.
                    if (key == Prefs.BAR_GRAPHITE) {
                        prefs.barOpacity = 1f
                        opacitySeek?.progress = 60
                    }
                    renderSwatches()
                    applyToBar()
                }
            }
            col.addView(Swatch(this, BarStyle.baseColor(this, key), key == prefs.barColorPreset),
                LinearLayout.LayoutParams(dp(34f), dp(34f)))
            col.addView(text(name, 11f, if (key == prefs.barColorPreset) fg else muted, Fonts.sans), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
            swatchRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun renderIconChips() {
        iconChips.removeAllViews()
        val options = mutableListOf(Prefs.ICONS_THEMED to "Nothing themed", Prefs.ICONS_ORIGINAL to "Original")
        IconPacks.installed(this).forEach { options.add(Prefs.ICONS_PACK_PREFIX + it.packageName to it.label) }
        options.forEach { (key, label) ->
            addChip(iconChips, chip(label, prefs.iconStyle == key) {
                prefs.iconStyle = key
                renderIconChips()
                adapter.notifyItemRangeChanged(1, shown.size)
                applyToBar()
            })
        }
    }

    private fun renderOpenChips() {
        openChips.removeAllViews()
        addChip(openChips, chip("Pop-up window", prefs.openAsPopUp) {
            prefs.openAsPopUp = true; renderOpenChips(); renderLongPressChips()
        })
        addChip(openChips, chip("Full screen", !prefs.openAsPopUp) {
            prefs.openAsPopUp = false; renderOpenChips(); renderLongPressChips()
        })
    }

    private fun renderLongPressChips() {
        longPressChips.removeAllViews()
        addChip(longPressChips, chip("Close app", !prefs.longPressOpensOtherWay) {
            prefs.longPressOpensOtherWay = false; renderLongPressChips()
        })
        val other = if (prefs.openAsPopUp) "Open full screen" else "Open as pop-up"
        addChip(longPressChips, chip(other, prefs.longPressOpensOtherWay) {
            prefs.longPressOpensOtherWay = true; renderLongPressChips()
        })
    }

    private fun renderShizuku() {
        val state = Shiz.state(this)
        shizukuStatus.text = when (state) {
            Shiz.State.NOT_INSTALLED -> "Optional. Install Shizuku to switch pop-ups without Nothing bubbles."
            Shiz.State.NOT_RUNNING -> "Shizuku isn't running. Open the Shizuku app and start it."
            Shiz.State.NEEDS_PERMISSION -> "Shizuku is running. Allow Cameo to use it."
            Shiz.State.CONNECTING -> "Connecting to Shizuku…"
            Shiz.State.CONNECTED -> "Connected. Pop-ups switch cleanly, ✕ closes the app, − opens it full screen."
        }
        shizukuStatus.setTextColor(if (state == Shiz.State.CONNECTED) fg else muted)
        shizukuRow.removeAllViews()
        when (state) {
            Shiz.State.NOT_RUNNING -> addChip(shizukuRow, chip("Open Shizuku", false) {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
            })
            Shiz.State.NEEDS_PERMISSION -> addChip(shizukuRow, chip("Allow", false) { Shiz.requestPermission() })
            Shiz.State.CONNECTED -> {
                addChip(shizukuRow, chip("✕ closes · − full screen", prefs.noBubbles) {
                    prefs.noBubbles = !prefs.noBubbles
                    Shiz.setBubbleGuard(prefs.noBubbles)
                    renderShizuku()
                })
                addChip(shizukuRow, chip("Smooth background", prefs.smoothBackground) {
                    prefs.smoothBackground = !prefs.smoothBackground
                    renderShizuku()
                })
            }
            else -> Unit
        }
        shizukuRow.visibility = if (shizukuRow.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun renderBarMode() {
        if (!::barModeStatus.isInitialized) return
        val a11y = Bar.accessibilityEnabled(this)
        barModeStatus.text = if (a11y) {
            "Accessibility overlay: on. No notifications."
        } else {
            "Accessibility overlay: off. Using \"display over other apps\", which shows a notification. " +
                "Turn on Cameo under Accessibility > Installed apps to drop it."
        }
        barModeStatus.setTextColor(if (a11y) fg else muted)
        hideNote.visibility = if (a11y) View.GONE else View.VISIBLE
    }

    /** Push colour / icon changes to the bar on screen straight away. */
    private fun applyToBar() {
        if (prefs.barEnabled) Bar.start(this)
    }

    private fun updateCounter() {
        counter.text = "${selected.size} / ${Prefs.MAX_APPS}"
        segments.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                cornerRadius = dp(3f).toFloat()
                setColor(if (i < selected.size) Color.parseColor("#E8E8E8") else Color.parseColor("#2A2A2A"))
            }
        }
    }

    // ----------------------------------------------------------------- data

    private fun loadApps() {
        io.execute {
            val list = Apps.launchable(this)
            runOnUiThread {
                all = list
                duplicateLabels = list.groupBy { it.label.lowercase() }.filter { it.value.size > 1 }.keys
                selected.retainAll { c -> list.any { it.component == c } }
                updateCounter()
                renderOrder()
                filter("")
            }
        }
    }

    private fun filter(q: String) {
        val query = q.trim()
        val old = shown.size
        shown = if (query.isEmpty()) all else all.filter { it.matches(query) }
        // Leave the header (and the search box's focus) alone.
        adapter.notifyItemRangeRemoved(1, old)
        adapter.notifyItemRangeInserted(1, shown.size)
    }

    private fun toggle(c: ComponentName) {
        if (c in selected) selected.remove(c)
        else if (selected.size < Prefs.MAX_APPS) selected.add(c)
        updateCounter()
        renderOrder()
        adapter.notifyItemRangeChanged(1, shown.size)
    }

    private fun save() {
        prefs.apps = selected.toList()
        if (prefs.barEnabled) Bar.start(this, expand = true)
        finish()
    }

    private fun askNotificationsOnce() {
        if (prefs.askedNotifications) return
        prefs.askedNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // -------------------------------------------------------------- adapter

    private class HeaderHolder(v: View) : RecyclerView.ViewHolder(v)

    private class AppHolder(
        val row: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val detail: TextView,
        val toggle: ToggleDot,
    ) : RecyclerView.ViewHolder(row) {
        var bound: String? = null
    }

    private inner class AppAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                (header.parent as? ViewGroup)?.removeView(header)
                return HeaderHolder(header)
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(50f)
                setPadding(dp(8f), dp(6f), dp(12f), dp(6f))
                val mask = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.WHITE) }
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#26FFFFFF")), null, mask)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(this@MainActivity)
            row.addView(icon, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val label = text("", 15f, fg, Fonts.sans).apply { isSingleLine = true }
            val detail = text("", 11f, muted, Fonts.sans).apply { isSingleLine = true }
            texts.addView(label)
            texts.addView(detail)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14f); rightMargin = dp(12f)
            })
            val toggle = ToggleDot(this@MainActivity)
            row.addView(toggle, LinearLayout.LayoutParams(dp(18f), dp(18f)))
            return AppHolder(row, icon, label, detail, toggle)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as? AppHolder ?: return
            val entry = shown[position - 1]
            val isOn = entry.component in selected
            val full = selected.size >= Prefs.MAX_APPS
            h.label.text = entry.label
            // Show the real app name when the launcher label differs, and the package when names clash.
            val extra = buildList {
                if (!entry.appLabel.equals(entry.label, ignoreCase = true)) add(entry.appLabel)
                if (entry.label.lowercase() in duplicateLabels) add(entry.component.packageName)
            }.joinToString(" · ")
            h.detail.text = extra
            h.detail.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
            h.toggle.on = isOn
            h.row.alpha = if (!isOn && full) 0.4f else 1f
            h.row.isEnabled = isOn || !full
            h.row.setOnClickListener { toggle(entry.component) }

            val style = prefs.iconStyle
            val key = entry.component.flattenToString() + style
            h.bound = key
            h.icon.setImageDrawable(null)
            val size = dp(32f)
            io.execute {
                val bmp = Apps.roundIcon(this@MainActivity, entry.component, size, style)
                runOnUiThread { if (h.bound == key) h.icon.setImageBitmap(bmp) }
            }
        }
    }

    /** 14dp circle: solid red when chosen, thin grey ring when not. */
    private class ToggleDot(context: Context) : View(context) {
        var on = false
            set(value) { field = value; invalidate() }
        private val d = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val r = 7f * d
            val cx = width / 2f
            val cy = height / 2f
            if (on) {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#D71921")
                canvas.drawCircle(cx, cy, r, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * d
                paint.color = Color.parseColor("#555555")
                canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2f, paint)
            }
        }
    }

    /** Colour swatch: filled circle with a ring when selected. */
    private class Swatch(context: Context, private val color: Int, private val selected: Boolean) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy)
            if (selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                paint.color = Color.parseColor("#D71921")
                canvas.drawCircle(cx, cy, r - d, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = color or 0xFF000000.toInt()
            canvas.drawCircle(cx, cy, r - 5f * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            paint.color = Color.parseColor("#33FFFFFF")
            canvas.drawCircle(cx, cy, r - 5f * d, paint)
        }
    }
}

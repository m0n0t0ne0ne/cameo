package io.github.m0n0t0ne0ne.floatbar

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.util.LruCache

/**
 * One launchable activity. [label] is what the launcher shows (the activity label); [appLabel] is
 * the application's own name, which can differ (SoundScream's launcher label is "SNDSCR").
 */
data class AppEntry(
    val component: ComponentName,
    val label: String,
    val appLabel: String,
) {
    fun matches(q: String) =
        label.contains(q, true) || appLabel.contains(q, true) || component.packageName.contains(q, true)
}

object Apps {
    private const val TAG = "Cameo"

    /** Nothing OS pop-up view mode: WindowConfiguration.WINDOWING_MODE_WINDOWFORM on this phone. */
    private const val WINDOWING_MODE_NT_POPUP = 102
    private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"

    /**
     * Close an app for real and forget its red dot. With Shizuku the task is removed and the app
     * force-stopped (works even while it's on screen); without it only a background process can be ended.
     */
    fun close(context: Context, pkg: String) {
        val svc = Shiz.service
        val tasks = Shiz.tasks()
        if (svc != null && tasks != null) {
            val mine = tasks.filter { it.pkg == pkg }
            try {
                if (mine.isEmpty()) svc.close(0, pkg) else mine.forEach { svc.close(it.id, pkg) }
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku close failed for $pkg", e)
            }
        } else {
            try {
                context.getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "could not close $pkg", e)
            }
        }
    }

    private val iconCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun clearIconCache() = iconCache.evictAll()

    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    it.loadLabel(pm).toString(),
                    it.activityInfo.applicationInfo.loadLabel(pm).toString(),
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun exists(context: Context, c: ComponentName): Boolean = try {
        context.packageManager.getActivityInfo(c, PackageManager.ComponentInfoFlags.of(0L))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** The app's icon in the chosen style, drawn into a circle of [sizePx]. */
    fun roundIcon(context: Context, c: ComponentName, sizePx: Int, style: String = Prefs(context).iconStyle): Bitmap? {
        val key = c.flattenToString() + "@" + sizePx + "@" + style
        iconCache.get(key)?.let { return it }
        val original = try {
            context.packageManager.getActivityIcon(c)
        } catch (_: Exception) {
            return null
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val half = sizePx / 2f
        canvas.clipPath(Path().apply { addCircle(half, half, half, Path.Direction.CW) })

        val drawn = when {
            style == Prefs.ICONS_THEMED -> drawThemed(context, canvas, original, sizePx)
            style.startsWith(Prefs.ICONS_PACK_PREFIX) ->
                IconPacks.icon(context, style.removePrefix(Prefs.ICONS_PACK_PREFIX), c)?.let {
                    it.setBounds(0, 0, sizePx, sizePx); it.draw(canvas); true
                } ?: false
            else -> false
        }
        if (!drawn) {
            original.setBounds(0, 0, sizePx, sizePx)
            original.draw(canvas)
        }
        iconCache.put(key, bmp)
        return bmp
    }

    /**
     * Nothing's themed look: the app's monochrome layer in system_neutral1_50 on system_neutral1_900
     * (the colours Nothing Launcher uses for its themed icon pack in dark mode).
     * Returns false when the app has no monochrome layer, so the original icon is used instead.
     */
    private fun drawThemed(context: Context, canvas: Canvas, icon: Drawable, size: Int): Boolean {
        val bg = context.getColor(android.R.color.system_neutral1_900)
        val fg = context.getColor(android.R.color.system_neutral1_50)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val mono = (icon as? AdaptiveIconDrawable)?.monochrome
        val glyph = if (mono != null) {
            val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val m = mono.mutate()
            m.colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
            // Adaptive layers are 108 units with the visible 72 in the middle; extend the bounds to match.
            val pad = size / 4
            m.setBounds(-pad, -pad, size + pad, size + pad)
            m.draw(Canvas(raw))
            raw
        } else {
            glyphFromColourIcon(icon, size, fg)
        }
        // Apps draw their glyphs at very different sizes inside the icon (a calculator's can be half
        // the size of a shield's), so scale every glyph to the same visual size.
        canvas.drawBitmap(normaliseGlyph(glyph, size), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return true
    }

    /** Re-centres a glyph and scales it so its larger side is [GLYPH_FRACTION] of the icon. */
    private fun normaliseGlyph(src: Bitmap, size: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 24) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < l || b < t) return src
        val gw = (r - l + 1).toFloat(); val gh = (b - t + 1).toFloat()
        val scale = size * GLYPH_FRACTION / maxOf(gw, gh)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dw = gw * scale; val dh = gh * scale
        val dst = android.graphics.RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f)
        Canvas(out).drawBitmap(src, Rect(l, t, r + 1, b + 1), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Visual size of every themed glyph, as a fraction of the icon circle. */
    private const val GLYPH_FRACTION = 0.5f

    /**
     * Apps without a monochrome layer: render the colour icon, then keep only what stands out from
     * its own background (by brightness) as a single-colour glyph, so it matches the themed icons.
     */
    private fun glyphFromColourIcon(icon: Drawable, size: Int, fg: Int): Bitmap {
        val src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        val inset = (size * 0.12f).toInt()
        c.clipPath(Path().apply { addCircle(size / 2f, size / 2f, size / 2f - inset, Path.Direction.CW) })
        icon.setBounds(inset, inset, size - inset, size - inset)
        icon.draw(c)

        val px = IntArray(size * size)
        src.getPixels(px, 0, size, 0, 0, size, size)
        fun lum(p: Int) = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f

        // The icon's own background: average brightness on a ring just inside the visible circle.
        var sum = 0f
        var n = 0
        val r = size / 2f - inset - 3f
        for (i in 0 until 48) {
            val a = i * Math.PI * 2 / 48
            val x = (size / 2f + r * Math.cos(a)).toInt().coerceIn(0, size - 1)
            val y = (size / 2f + r * Math.sin(a)).toInt().coerceIn(0, size - 1)
            val p = px[y * size + x]
            if ((p ushr 24) > 128) { sum += lum(p); n++ }
        }
        val ref = if (n > 0) sum / n else 0f
        val fgRgb = fg and 0x00FFFFFF
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) / 255f
            val contrast = (kotlin.math.abs(lum(p) - ref) * 2.2f).coerceIn(0f, 1f)
            val outA = (a * contrast * 255).toInt()
            px[i] = (outA shl 24) or fgRgb
        }
        src.setPixels(px, 0, size, 0, 0, size, size)
        return src
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun launch(
        context: Context,
        c: ComponentName,
        asPopUp: Boolean,
        index: Int,
    ) {
        if (Shiz.service == null) {
            startNormally(context, c, asPopUp, index)
            return
        }
        // Task juggling goes through Shizuku binder calls, so keep it off the main thread.
        worker.execute {
            if (!launchWithShizuku(context, c, asPopUp)) main.post { startNormally(context, c, asPopUp, index) }
        }
    }


    private fun startNormally(context: Context, c: ComponentName, asPopUp: Boolean, index: Int) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(c)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Apps started from outside Nothing's launcher are created full screen and then shrunk into
        // the pop-up; skipping the open animation makes the window appear straight in its pop-up
        // spot (a custom transition is overridden by Nothing and brings the slide-up back).
        if (asPopUp) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val options = if (asPopUp) popUpOptions(context, index) else null
        try {
            context.startActivity(intent, options)
        } catch (e: Exception) {
            Log.w(TAG, "pop-up launch failed for $c, retrying normally", e)
            context.startActivity(intent)
        }
    }

    /**
     * With Shizuku (worker thread):
     *  - an app already showing as a pop-up is left as it is;
     *  - any other pop-up is sent to the background first, and we wait until it has settled, so
     *    Nothing neither bubbles it nor plays its exit animation over the new window (that collision
     *    left new pop-ups invisible with only their drag handle showing);
     *  - an app waiting in the background (closed with ✕) is brought back where it left off;
     *  - an app that is open full screen is turned into a pop-up.
     * Returns false when the app should be started normally.
     */
    private fun launchWithShizuku(context: Context, c: ComponentName, asPopUp: Boolean): Boolean {
        val svc = Shiz.service ?: return false
        val tasks = Shiz.tasks() ?: return false
        return try {
            val existing = tasks.filter { it.pkg == c.packageName }
            if (asPopUp && existing.any { it.mode == WINDOWING_MODE_NT_POPUP && it.visible }) return true

            val others = if (asPopUp) tasks.filter { it.mode == WINDOWING_MODE_NT_POPUP && it.pkg != c.packageName } else emptyList()
            others.forEach { svc.moveToBack(it.id) }

            // Prefer the app's open task; otherwise the one waiting in the background (closed with ✕).
            val reuse = existing.firstOrNull { it.visible && it.mode != WINDOWING_MODE_NT_POPUP }
                ?: existing.firstOrNull()

            // Wait (up to ~1.2 s) until the old pop-ups have settled in the background.
            val othersIds = others.map { it.id }.toSet()
            if (othersIds.isNotEmpty()) {
                for (i in 0 until 12) {
                    val now = Shiz.tasks() ?: break
                    if (now.none { it.id in othersIds && (it.mode == WINDOWING_MODE_NT_POPUP || it.visible) }) break
                    Thread.sleep(100)
                }
                Thread.sleep(200) // let the exit transition finish drawing
            }

            if (reuse != null) {
                // Brings it back where it left off; Nothing plays its own pop-up entrance for this.
                return svc.restore(reuse.id, if (asPopUp) WINDOWING_MODE_NT_POPUP else 1) >= 0
            }
            false // start fresh
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku launch failed for $c", e)
            false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun popUpOptions(context: Context, index: Int): Bundle {
        // No launch bounds: Nothing sizes and places pop-up windows itself.
        return ActivityOptions.makeBasic().toBundle().apply { putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_NT_POPUP) }
    }

}

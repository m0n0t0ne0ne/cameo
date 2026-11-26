package io.github.m0n0t0ne0ne.floatbar

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color

/** Colours for the bar, derived from the user's colour preset and opacity. */
data class BarStyle(
    val fill: Int,
    val dot: Int,
    val accent: Int,
    val outline: Int,
) {
    companion object {
        private val RED = Color.parseColor("#D71921")

        fun baseColor(context: Context, preset: String): Int = when (preset) {
            // The same colour Nothing uses for folders, widgets and themed icon backgrounds
            // (system_neutral1_900: #181C1E with your current wallpaper), so it follows theme changes.
            Prefs.BAR_GRAPHITE -> context.getColor(android.R.color.system_neutral1_900)
            Prefs.BAR_WHITE -> Color.parseColor("#F2F2F2")
            Prefs.BAR_RED -> RED
            Prefs.BAR_WALLPAPER -> try {
                WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor?.toArgb() ?: Color.BLACK
            } catch (_: Exception) {
                Color.BLACK
            }
            else -> Color.BLACK
        }

        fun from(context: Context, prefs: Prefs = Prefs(context)): BarStyle {
            val base = baseColor(context, prefs.barColorPreset)
            val alpha = (prefs.barOpacity * 255).toInt().coerceIn(0, 255)
            val fill = (base and 0x00FFFFFF) or (alpha shl 24)
            val light = Color.luminance(base) > 0.5f
            val isRed = prefs.barColorPreset == Prefs.BAR_RED
            return BarStyle(
                fill = fill,
                dot = if (light) Color.parseColor("#1A1A1A") else Color.parseColor("#E8E8E8"),
                accent = if (isRed) Color.WHITE else RED,
                outline = if (light) Color.parseColor("#26000000") else Color.parseColor("#26FFFFFF"),
            )
        }
    }
}

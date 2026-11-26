package io.github.m0n0t0ne0ne.floatbar

import android.content.ComponentName
import android.content.Context

/** Everything the bar remembers between runs. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("floatbar", Context.MODE_PRIVATE)

    var apps: List<ComponentName>
        get() = (sp.getString(KEY_APPS, "") ?: "")
            .split('|')
            .mapNotNull { ComponentName.unflattenFromString(it) }
        set(value) = sp.edit()
            .putString(KEY_APPS, value.take(MAX_APPS).joinToString("|") { it.flattenToString() })
            .apply()

    /** true = docked on the left edge, false = right edge. */
    var onLeft: Boolean
        get() = sp.getBoolean(KEY_LEFT, false)
        set(value) = sp.edit().putBoolean(KEY_LEFT, value).apply()

    /** Centre of the collapsed bar as a fraction of screen height. */
    var centerYFraction: Float
        get() = sp.getFloat(KEY_Y, 0.42f)
        set(value) = sp.edit().putFloat(KEY_Y, value.coerceIn(0f, 1f)).apply()

    var barEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Open apps in Nothing's pop-up window (true) or normally, full screen (false). */
    var openAsPopUp: Boolean
        get() = sp.getBoolean(KEY_POPUP, true)
        set(value) = sp.edit().putBoolean(KEY_POPUP, value).apply()

    /** Long press on a dock icon: true = open the app the other way (see [openAsPopUp]), false = close it. */
    var longPressOpensOtherWay: Boolean
        get() = sp.getBoolean(KEY_LONG_PRESS_OTHER, false)
        set(value) = sp.edit().putBoolean(KEY_LONG_PRESS_OTHER, value).apply()

    /** One of the BAR_* presets. */
    var barColorPreset: String
        get() = sp.getString(KEY_BAR_COLOR, BAR_BLACK) ?: BAR_BLACK
        set(value) = sp.edit().putString(KEY_BAR_COLOR, value).apply()

    /** Bar opacity, 0.4–1.0. */
    var barOpacity: Float
        get() = sp.getFloat(KEY_BAR_OPACITY, 0.7f)
        set(value) = sp.edit().putFloat(KEY_BAR_OPACITY, value.coerceIn(0.4f, 1f)).apply()

    /** ICONS_THEMED, ICONS_ORIGINAL, or ICONS_PACK_PREFIX + package. */
    var iconStyle: String
        get() = sp.getString(KEY_ICONS, ICONS_THEMED) ?: ICONS_THEMED
        set(value) = sp.edit().putString(KEY_ICONS, value).apply()

    /** With Shizuku: send apps Nothing would turn into floating bubbles to the background instead. */
    var noBubbles: Boolean
        get() = sp.getBoolean(KEY_NO_BUBBLES, true)
        set(value) = sp.edit().putBoolean(KEY_NO_BUBBLES, value).apply()

    /** Experimental: hide the launcher's wallpaper jump behind a snapshot (needs Shizuku). */
    var smoothBackground: Boolean
        get() = sp.getBoolean("smooth_background", false)
        set(value) = sp.edit().putBoolean("smooth_background", value).apply()

    var running: Set<String>
        get() = sp.getStringSet(KEY_RUNNING, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_RUNNING, value).apply()

    /** One-time: Graphite now matches Nothing's opaque folder colour, so make it fully opaque once. */
    fun migrateGraphiteOpacity() {
        if (sp.getBoolean("graphite_v2", false)) return
        if (barColorPreset == BAR_GRAPHITE) barOpacity = 1f
        sp.edit().putBoolean("graphite_v2", true).apply()
    }

    var askedNotifications: Boolean
        get() = sp.getBoolean(KEY_ASKED_NOTIF, false)
        set(value) = sp.edit().putBoolean(KEY_ASKED_NOTIF, value).apply()

    companion object {
        const val MAX_APPS = 10

        const val BAR_BLACK = "black"
        const val BAR_GRAPHITE = "graphite"
        const val BAR_WHITE = "white"
        const val BAR_RED = "red"
        const val BAR_WALLPAPER = "wallpaper"

        const val ICONS_THEMED = "themed"
        const val ICONS_ORIGINAL = "original"
        const val ICONS_PACK_PREFIX = "pack:"

        private const val KEY_BAR_COLOR = "bar_color"
        private const val KEY_BAR_OPACITY = "bar_opacity"
        private const val KEY_ICONS = "icons"
        private const val KEY_RUNNING = "running"
        private const val KEY_NO_BUBBLES = "no_bubbles"
        private const val KEY_APPS = "apps"
        private const val KEY_LEFT = "on_left"
        private const val KEY_Y = "center_y"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_POPUP = "open_as_popup"
        private const val KEY_LONG_PRESS_OTHER = "long_press_other_way"
        private const val KEY_ASKED_NOTIF = "asked_notif"
    }
}

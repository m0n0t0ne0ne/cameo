package io.github.m0n0t0ne0ne.floatbar

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Shows / hides the bar through whichever host is available. */
object Bar {

    fun component(context: Context) = ComponentName(context, BarAccessibilityService::class.java)

    /** The user has switched Cameo on under Settings > Accessibility. */
    fun accessibilityEnabled(context: Context): Boolean {
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = component(context)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** The bar can be shown at all (accessibility service on, or overlay permission granted). */
    fun canShow(context: Context) =
        BarAccessibilityService.instance != null || accessibilityEnabled(context) || Settings.canDrawOverlays(context)

    fun start(context: Context, expand: Boolean = false) {
        val a11y = BarAccessibilityService.instance
        when {
            a11y != null -> {
                a11y.show(expand)
                BarService.stop(context)
            }
            // Switched on but not connected yet: it shows the bar itself when it connects.
            accessibilityEnabled(context) -> Unit
            Settings.canDrawOverlays(context) -> BarService.start(context, expand)
        }
    }

    fun stop(context: Context) {
        BarAccessibilityService.instance?.hide()
        BarService.stop(context)
    }
}

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

    /**
     * Switch on Cameo's accessibility service directly. Needs WRITE_SECURE_SETTINGS (granted through
     * Shizuku). Once it connects, the overlay fallback and both of its notifications go away.
     */
    fun enableAccessibility(context: Context): Boolean {
        if (accessibilityEnabled(context)) return true
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.filter { it.isNotBlank() } ?: emptyList()
            val me = component(context).flattenToString()
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (current + me).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: SecurityException) {
            false
        }
    }

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

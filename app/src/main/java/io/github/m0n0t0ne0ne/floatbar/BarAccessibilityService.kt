package io.github.m0n0t0ne0ne.floatbar

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Preferred host for the bar. Cameo uses this only to draw its edge bar on top of other apps:
 * it asks for no events and can't read window content. Accessibility overlays don't make Android
 * post "Cameo is displaying over other apps", and need no foreground service or notification.
 */
class BarAccessibilityService : AccessibilityService() {

    private var controller: BarController? = null

    // Accessibility overlays draw above the lock screen, unlike the old app overlay. Hide the bar
    // there so it behaves as before.
    private val lockWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = syncLock()
    }
    private var watching = false

    private fun syncLock() {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        controller?.setHidden(locked)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!watching) {
            registerReceiver(lockWatcher, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
            watching = true
        }
        // Take over from the overlay fallback, if it was running.
        BarService.stop(this)
        if (Prefs(this).barEnabled) show(false)
    }

    /** Show the bar (or refresh it if it's already up). */
    fun show(expand: Boolean) {
        val c = controller
        if (c != null) {
            c.refresh(expand)
            return
        }
        controller = BarController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            .takeIf { it.attached }
        syncLock()
        if (expand) controller?.refresh(true)
    }

    fun hide() {
        controller?.destroy()
        controller = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (watching) {
            try { unregisterReceiver(lockWatcher) } catch (_: Exception) {}
            watching = false
        }
        hide()
    }

    companion object {
        /** The connected service, if the user has it switched on. Main thread only. */
        @Volatile
        var instance: BarAccessibilityService? = null
            private set
    }
}

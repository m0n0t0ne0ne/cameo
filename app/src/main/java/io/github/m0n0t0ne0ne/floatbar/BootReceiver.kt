package io.github.m0n0t0ne0ne.floatbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // With the accessibility service on, the system starts it and it shows the bar itself.
        if (Prefs(context).barEnabled) Bar.start(context)
    }
}

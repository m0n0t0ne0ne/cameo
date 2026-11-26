package io.github.m0n0t0ne0ne.floatbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/**
 * Fallback host for the bar when Cameo's accessibility service is off: an app overlay kept
 * alive by a foreground service. In this mode Android also shows "displaying over other apps".
 */
class BarService : Service() {

    private var controller: BarController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        if (!Settings.canDrawOverlays(this) || BarAccessibilityService.instance != null) {
            stopSelf()
            return
        }
        controller = BarController(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            .takeIf { it.attached }
        if (controller == null) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller ?: return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> c.refresh(intent.getBooleanExtra(EXTRA_EXPAND, false))
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        super.onDestroy()
    }

    // --------------------------------------------------------- notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Floating bar", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the floating bar on screen"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Cameo is on")
            .setContentText("Tap to choose apps")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        const val CHANNEL = "bar"
        private const val NOTIFICATION_ID = 1
        const val ACTION_REFRESH = "io.github.m0n0t0ne0ne.floatbar.REFRESH"
        const val ACTION_STOP = "io.github.m0n0t0ne0ne.floatbar.STOP"
        const val EXTRA_EXPAND = "expand"

        fun start(context: Context, expand: Boolean = false) {
            val i = Intent(context, BarService::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(EXTRA_EXPAND, expand)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BarService::class.java))
        }
    }
}

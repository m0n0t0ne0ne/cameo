package io.github.m0n0t0ne0ne.floatbar

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku connection. When it's available Cameo can manage tasks properly
 * (switch pop-ups without bubbles, accurate red dots, close on-screen apps). When it isn't,
 * everything falls back to the plain behaviour.
 */
object Shiz {
    private const val TAG = "Cameo"
    private const val REQUEST_CODE = 42

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, CONNECTED }

    @Volatile var service: ITaskService? = null
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private var appContext: Context? = null
    private var bound = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = listeners.toList().forEach { it() }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, TaskService::class.java.name))
            .daemon(false)
            .processNameSuffix("tasks")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            service = ITaskService.Stub.asInterface(binder)
            appContext?.let { ctx ->
                val prefs = Prefs(ctx)
                runCatching { service?.setManagedPackages(prefs.apps.joinToString(",") { it.packageName }) }
                if (prefs.noBubbles) runCatching { service?.setBubbleGuard(true) }
            }
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            notifyChanged()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { connectIfAllowed(); notifyChanged() }
    private val binderDead = Shizuku.OnBinderDeadListener { service = null; bound = false; notifyChanged() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE && result == PackageManager.PERMISSION_GRANTED) connectIfAllowed()
        notifyChanged()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun state(context: Context): State {
        val installed = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }
        if (!installed) return State.NOT_INSTALLED
        if (!Shizuku.pingBinder()) return State.NOT_RUNNING
        if (Shizuku.isPreV11()) return State.NOT_RUNNING
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.NEEDS_PERMISSION
        return if (service != null) State.CONNECTED else State.CONNECTING
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    fun connectIfAllowed() {
        try {
            if (bound || !Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            Shizuku.bindUserService(args, connection)
            bound = true
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku bind failed", e)
        }
    }

    fun setManagedPackages(pkgs: List<String>) {
        runCatching { service?.setManagedPackages(pkgs.joinToString(",")) }
    }

    fun setBubbleGuard(on: Boolean) {
        runCatching { service?.setBubbleGuard(on) }
    }

    /** Screen snapshot via the shell service (about 0.1 s), or null. Call off the main thread. */
    fun captureScreen(): android.graphics.Bitmap? {
        val s = service ?: return null
        return try {
            val b = s.captureScreen() ?: return null
            val w = b.getInt("w"); val h = b.getInt("h")
            @Suppress("DEPRECATION")
            val mem = b.getParcelable<android.os.SharedMemory>("mem") ?: return null
            val buf = mem.mapReadOnly()
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            android.os.SharedMemory.unmap(buf)
            mem.close()
            bmp
        } catch (e: Throwable) {
            Log.w(TAG, "captureScreen failed", e)
            null
        }
    }

    // ---------------------------------------------------------------- helpers

    data class Task(val id: Int, val mode: Int, val visible: Boolean, val pkg: String)

    fun tasks(): List<Task>? {
        val s = service ?: return null
        return try {
            s.listTasks().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split('|')
                if (p.size < 4) null else Task(p[0].toInt(), p[1].toInt(), p[2].toBoolean(), p[3])
            }.toList()
        } catch (e: Throwable) {
            Log.w(TAG, "listTasks failed", e)
            null
        }
    }
}

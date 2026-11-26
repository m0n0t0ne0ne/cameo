package io.github.m0n0t0ne0ne.floatbar

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku user service. Runs in a separate process with shell (adb) privileges, so it can use the
 * task APIs that apps normally can't: list every task, move a pop-up to the background, bring a
 * task back as a pop-up, remove Nothing's floating bubbles and close apps.
 *
 * Everything here goes through reflection because these are hidden framework APIs.
 */
class TaskService : ITaskService.Stub() {

    private val atm: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private val wctClass by lazy { Class.forName("android.window.WindowContainerTransaction") }
    private val tokenClass by lazy { Class.forName("android.window.WindowContainerToken") }

    @Volatile private var guardThread: Thread? = null

    override fun destroy() {
        setBubbleGuard(false)
        exitProcess(0)
    }

    // ------------------------------------------------------------------ tasks

    private class TaskInfo(val id: Int, val mode: Int, val visible: Boolean, val pkg: String, val token: Any?)

    private fun method(target: Any, name: String, params: Int = -1): Method =
        target.javaClass.methods.first { it.name == name && (params < 0 || it.parameterCount == params) }

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }.get(o)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun tasks(): List<TaskInfo> {
        val m = method(atm, "getTasks")
        val args = m.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> 200
                t == Boolean::class.javaPrimitiveType -> false
                t == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }.toTypedArray()
        val list = m.invoke(atm, *args) as List<*>
        return list.mapNotNull { t ->
            t ?: return@mapNotNull null
            val id = field(t, "taskId") as Int
            val cfg = field(t, "configuration")
            val wc = cfg?.javaClass?.getField("windowConfiguration")?.get(cfg)
            val mode = wc?.javaClass?.getMethod("getWindowingMode")?.invoke(wc) as? Int ?: 0
            val visible = field(t, "isVisible") as? Boolean ?: false
            val comp = (field(t, "baseActivity") ?: field(t, "topActivity")) as? android.content.ComponentName
            TaskInfo(id, mode, visible, comp?.packageName ?: "", field(t, "token"))
        }
    }

    override fun listTasks(): String = try {
        tasks().joinToString("\n") { "${it.id}|${it.mode}|${it.visible}|${it.pkg}" }
    } catch (e: Throwable) {
        Log.w(TAG, "listTasks", e)
        ""
    }

    private fun apply(build: (wct: Any) -> Unit) {
        val wct = wctClass.getConstructor().newInstance()
        build(wct)
        val ctl = method(atm, "getWindowOrganizerController").invoke(atm)!!
        method(ctl, "applyTransaction", 1).invoke(ctl, wct)
    }

    private fun setMode(wct: Any, token: Any, mode: Int, toBack: Boolean) {
        wctClass.getMethod("setWindowingMode", tokenClass, Int::class.javaPrimitiveType).invoke(wct, token, mode)
        if (toBack) wctClass.getMethod("reorder", tokenClass, Boolean::class.javaPrimitiveType).invoke(wct, token, false)
    }

    override fun moveToBack(taskId: Int) {
        try {
            val token = tasks().firstOrNull { it.id == taskId }?.token ?: return
            apply { setMode(it, token, WINDOWING_MODE_FULLSCREEN, toBack = true) }
        } catch (e: Throwable) {
            Log.w(TAG, "moveToBack", e)
        }
    }

    override fun unbubble(taskId: Int) {
        try {
            val token = tasks().firstOrNull { it.id == taskId }?.token ?: return
            // Nothing clears a task's bubble when it leaves pop-up mode, so pass through it while
            // staying at the back, then settle in full-screen mode.
            apply { setMode(it, token, WINDOWING_MODE_NT_POPUP, toBack = true) }
            apply { setMode(it, token, WINDOWING_MODE_FULLSCREEN, toBack = true) }
        } catch (e: Throwable) {
            Log.w(TAG, "unbubble", e)
        }
    }

    override fun restore(taskId: Int, windowingMode: Int): Int = try {
        val ao = Class.forName("android.app.ActivityOptions")
        val opts = ao.getMethod("makeBasic").invoke(null)
        ao.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, windowingMode)
        val bundle = ao.getMethod("toBundle").invoke(opts)
        method(atm, "startActivityFromRecents").invoke(atm, taskId, bundle) as Int
    } catch (e: Throwable) {
        Log.w(TAG, "restore", e)
        -1
    }

    override fun close(taskId: Int, pkg: String) {
        try {
            if (taskId > 0) method(atm, "removeTask").invoke(atm, taskId)
        } catch (e: Throwable) {
            Log.w(TAG, "removeTask", e)
        }
        if (pkg.matches(Regex("[A-Za-z0-9_.]+"))) {
            try {
                Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg)).waitFor()
            } catch (e: Throwable) {
                Log.w(TAG, "force-stop", e)
            }
        }
    }

    override fun grantSecureSettings(): Boolean = try {
        Runtime.getRuntime().exec(arrayOf(
            "pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.WRITE_SECURE_SETTINGS"
        )).waitFor() == 0
    } catch (e: Throwable) {
        Log.w(TAG, "grantSecureSettings", e)
        false
    }

    // ------------------------------------------------------------ screen snapshot

    /** Raw screencap (16-byte header + RGBA pixels) copied into shared memory for the app. */
    override fun captureScreen(): android.os.Bundle? = try {
        val p = Runtime.getRuntime().exec(arrayOf("screencap"))
        val input = java.io.BufferedInputStream(p.inputStream, 1 shl 20)
        val header = ByteArray(16)
        var read = 0
        while (read < 16) { val n = input.read(header, read, 16 - read); if (n < 0) break; read += n }
        val hb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = hb.getInt(0); val h = hb.getInt(4)
        val size = w * h * 4
        val mem = android.os.SharedMemory.create("floatbar-snap", size)
        val buf = mem.mapReadWrite()
        val chunk = ByteArray(1 shl 18)
        var left = size
        while (left > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, left))
            if (n < 0) break
            buf.put(chunk, 0, n); left -= n
        }
        android.os.SharedMemory.unmap(buf)
        p.destroy()
        mem.setProtect(android.system.OsConstants.PROT_READ)
        android.os.Bundle().apply { putInt("w", w); putInt("h", h); putParcelable("mem", mem) }
    } catch (e: Throwable) {
        Log.w(TAG, "captureScreen", e)
        null
    }

    // ------------------------------------------------------------ close guard

    @Volatile private var managed: Set<String> = emptySet()

    override fun setManagedPackages(csv: String) {
        managed = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    override fun removeTask(taskId: Int) {
        try {
            method(atm, "removeTask").invoke(atm, taskId)
        } catch (e: Throwable) {
            Log.w(TAG, "removeTask", e)
        }
    }

    /**
     * Watches Nothing's pop-up log for the apps on the bar:
     *  - minimise ("showAsFloatingIcon"): clear the bubble and open the app full screen instead;
     *  - close ("tryExitPopUpView" without a bubble): if the app ended up hidden in the background,
     *    remove its task so it's really closed. If it went full screen (still visible), leave it.
     */
    override fun setBubbleGuard(on: Boolean) {
        if (!on) {
            guardThread = null
            return
        }
        if (guardThread != null) return
        val t = Thread {
            // Poll the log ~8 times a second: a streaming logcat pipe is block-buffered.
            val re = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+).*(showAsFloatingIcon\(\): getTask|tryExitPopUpView task)=Task\{\w+ #(\d+)""")
            var since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val seen = LinkedHashSet<String>()
            val minimised = HashMap<Int, Long>() // task id -> when it was turned into a bubble
            try {
                while (guardThread === Thread.currentThread()) {
                    val p = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-v", "time", "-T", since, "-s",
                            "FloatingViewLayerController:D", "NtPopUpViewImpl:D")
                    )
                    val lines = BufferedReader(InputStreamReader(p.inputStream)).readLines()
                    p.waitFor()
                    val toFull = LinkedHashSet<Int>()
                    val exited = LinkedHashSet<Int>()
                    for (line in lines) {
                        val m = re.find(line) ?: continue
                        since = m.groupValues[1]
                        if (!seen.add(line)) continue // -T repeats the line at "since"
                        if (seen.size > 200) seen.remove(seen.first())
                        val id = m.groupValues[3].toIntOrNull() ?: continue
                        if (m.groupValues[2].startsWith("showAsFloatingIcon")) {
                            toFull += id
                            minimised[id] = System.currentTimeMillis()
                        } else {
                            exited += id
                        }
                    }
                    val now = System.currentTimeMillis()
                    minimised.entries.removeAll { now - it.value > 5000 }
                    // Minimise -> full screen straight away, before Nothing's bubble animation gets far.
                    if (toFull.isNotEmpty()) {
                        val tasks = tasks()
                        for (id in toFull) {
                            val task = tasks.firstOrNull { it.id == id } ?: continue
                            if (task.pkg !in managed) continue
                            // Clear the bubble and park the task at the back first. Bringing it
                            // forward while Nothing's minimise animation is still running left the
                            // app drawn at pop-up size on a black screen.
                            unbubble(id)
                            Thread.sleep(250)
                            restore(id, WINDOWING_MODE_FULLSCREEN)
                        }
                    }
                    // The pop-up's close button (tryExitPopUpView without a bubble) is left alone:
                    // Nothing already sends the app to the background, and tapping it in the bar
                    // brings it back where it left off. Long-press in the bar closes an app for real.
                    Thread.sleep(120)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "guard stopped", e)
            }
        }
        guardThread = t
        t.isDaemon = true
        t.start()
    }

    companion object {
        private const val TAG = "CameoTasks"
        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_NT_POPUP = 102
    }
}

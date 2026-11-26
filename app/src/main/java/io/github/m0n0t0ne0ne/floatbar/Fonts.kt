package io.github.m0n0t0ne0ne.floatbar

import android.graphics.Typeface
import java.io.File

/**
 * Uses the fonts already on the phone instead of bundling copies: the Nothing dot-matrix face for
 * headings and the system UI sans (Geist on Nothing OS 5) for body text.
 */
object Fonts {
    val dot: Typeface by lazy {
        load("/system/fonts/NDot57Caps.otf", "/system/fonts/Ndot-57.otf", "/system/fonts/NDot55Caps.otf")
            ?: Typeface.MONOSPACE
    }

    val sans: Typeface by lazy {
        load("/system/fonts/Geist-Regular.ttf") ?: Typeface.DEFAULT
    }

    private fun load(vararg paths: String): Typeface? {
        for (p in paths) {
            try {
                val f = File(p)
                if (f.canRead()) return Typeface.createFromFile(f)
            } catch (_: Exception) {
            }
        }
        return null
    }
}

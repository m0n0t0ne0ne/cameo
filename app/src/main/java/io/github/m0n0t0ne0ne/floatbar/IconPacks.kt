package io.github.m0n0t0ne0ne.floatbar

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** Third-party launcher icon packs (ADW / Nova / GO format: appfilter.xml). */
object IconPacks {

    data class Pack(val packageName: String, val label: String)

    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.teslacoilsw.launcher.THEME",
    )

    private class Loaded(val res: Resources, val map: Map<String, String>)

    private val loaded = mutableMapOf<String, Loaded?>()

    fun installed(context: Context): List<Pack> {
        val pm = context.packageManager
        return ACTIONS.flatMap { action ->
            pm.queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(0L))
        }
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { Pack(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    @SuppressLint("DiscouragedApi")
    fun icon(context: Context, packPackage: String, c: ComponentName): Drawable? {
        val pack = synchronized(loaded) { loaded.getOrPut(packPackage) { load(context, packPackage) } } ?: return null
        val name = pack.map["ComponentInfo{${c.packageName}/${c.className}}"]
            ?: pack.map.entries.firstOrNull { it.key.startsWith("ComponentInfo{${c.packageName}/") }?.value
            ?: return null
        val id = pack.res.getIdentifier(name, "drawable", packPackage)
        if (id == 0) return null
        return try { pack.res.getDrawable(id, null) } catch (_: Exception) { null }
    }

    fun forget() = synchronized(loaded) { loaded.clear() }

    @SuppressLint("DiscouragedApi")
    private fun load(context: Context, pkg: String): Loaded? {
        return try {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val map = HashMap<String, String>()
            val xmlId = res.getIdentifier("appfilter", "xml", pkg)
            val parser: XmlPullParser = if (xmlId != 0) {
                res.getXml(xmlId)
            } else {
                Xml.newPullParser().apply { setInput(res.assets.open("appfilter.xml"), "UTF-8") }
            }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (comp != null && drawable != null) map[comp] = drawable
                }
            }
            Loaded(res, map)
        } catch (_: Exception) {
            null
        }
    }
}

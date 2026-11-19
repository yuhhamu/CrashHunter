package dev.crashhunter.app

import dev.crashhunter.core.mods.Versions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.swing.SwingUtilities

object UpdateCheck {
    const val API = "https://api.github.com/repos/yuhhamu/CrashHunter/releases/latest"

    data class Release(val version: String, val url: String, val assets: Map<String, String> = emptyMap())

    fun parse(json: String): Release? {
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        if (o["draft"]?.jsonPrimitive?.booleanOrNull == true || o["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v")?.removePrefix("V") ?: return null
        val url = o["html_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://github.com/") } ?: Links.REPO + "/releases"
        val assets = runCatching {
            o["assets"]!!.jsonArray.mapNotNull { a ->
                val n = a.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val u = a.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://github.com/") } ?: return@mapNotNull null
                n to u
            }.toMap()
        }.getOrDefault(emptyMap())
        return Release(tag, url, assets)
    }

    fun isNewer(remote: String, current: String): Boolean = Versions.compare(remote, current) > 0

    fun start(ctx: AppContext, onFound: (Release) -> Unit) {
        if (!ctx.settings.checkUpdates) return
        Thread {
            val text = runCatching { kotlinx.coroutines.runBlocking { ctx.http.get(API, mapOf("Accept" to "application/vnd.github+json")) } }.getOrNull() ?: return@Thread
            val r = parse(text) ?: return@Thread
            if (isNewer(r.version, VERSION)) SwingUtilities.invokeLater { onFound(r) }
        }.apply { isDaemon = true; name = "update-check" }.start()
    }
}

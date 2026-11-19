package dev.crashhunter.core.analyze

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SolutionKb(private val entries: Map<String, JsonObject>, val lang: String) {
    data class Rendered(val title: String, val detail: String, val solutions: List<String>, val afterBisect: List<String> = solutions)

    fun render(cause: Cause, params: Map<String, String?>): Rendered {
        val e = entries[cause.name] ?: entries[Cause.UNKNOWN.name]!!
        fun text(o: Any?): String {
            val obj = o as? JsonObject ?: return ""
            return obj[lang]?.jsonPrimitive?.contentOrNull ?: obj["en"]?.jsonPrimitive?.contentOrNull ?: ""
        }
        val title = text(e["title"])
        val detail = fill(text(e["detail"]), params)
        val items = (e["solutions"] as? JsonArray)?.map { it to fill(text(it), params) }?.filter { it.second.isNotBlank() } ?: emptyList()
        fun isBisect(o: Any?) = (o as? JsonObject)?.get("bisect")?.jsonPrimitive?.contentOrNull == "true"
        return Rendered(title, detail, items.map { it.second }, items.filterNot { isBisect(it.first) }.map { it.second })
    }

    private fun fill(template: String, params: Map<String, String?>): String {
        var s = template
        for ((k, v) in params) if (!v.isNullOrBlank()) s = s.replace("{$k}", v)
        s = s.replace(Regex("""[(（]\{\w+\}[)）]"""), "").replace(Regex("""\s?\{\w+\}"""), "")
        return s.replace(Regex("""\s{2,}"""), " ").replace(" 。", "。").trim()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(lang: String, userFile: File? = null): SolutionKb {
            val builtIn = SolutionKb::class.java.getResourceAsStream("/crashhunter/solutions.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val map = LinkedHashMap<String, JsonObject>()
            json.parseToJsonElement(builtIn).jsonObject.forEach { (k, v) -> map[k] = v.jsonObject }
            if (userFile != null && userFile.isFile) {
                runCatching { json.parseToJsonElement(userFile.readText()).jsonObject }.getOrNull()?.forEach { (k, v) -> (v as? JsonObject)?.let { map[k] = it } }
            }
            return SolutionKb(map, if (lang == "ja") "ja" else "en")
        }
    }
}

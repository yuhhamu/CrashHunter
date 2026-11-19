package dev.crashhunter.core.verify

import dev.crashhunter.core.db.DbRefs
import dev.crashhunter.core.db.ModRef
import dev.crashhunter.core.fix.remote.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

@Serializable
data class PendingFile(val mod: ModRef, val reason: String, val n: Int = 0, val envs: List<String> = emptyList(), val tried: Long? = null)

@Serializable
data class VerdictBody(val mod: ModRef, val reason: String, val verdict: String, val method: String, val detail: String? = null, val tool: String? = null)

enum class Verdict { CONFIRMED, REJECTED, INCONCLUSIVE }

data class VerifyOutcome(val verdict: Verdict, val method: String, val detail: String)

interface FileVerifier {
    fun supports(reason: String): Boolean
    suspend fun verify(target: PendingFile, jar: File): VerifyOutcome
}

object StaticVerifier : FileVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override fun supports(reason: String) = reason == "NO_REFMAP" || reason == "BROKEN"

    override suspend fun verify(target: PendingFile, jar: File): VerifyOutcome = when (target.reason) {
        "BROKEN" -> corrupt(jar)?.let { VerifyOutcome(Verdict.CONFIRMED, "static", "jar cannot be read: $it") }
            ?: VerifyOutcome(Verdict.REJECTED, "static", "all entries read without errors")
        "NO_REFMAP" -> refmap(jar)
        else -> VerifyOutcome(Verdict.INCONCLUSIVE, "static", "needs a launch test")
    }

    fun corrupt(jar: File): String? = runCatching {
        ZipFile(jar).use { zip ->
            val buf = ByteArray(1 shl 16)
            for (e in zip.entries()) if (!e.isDirectory) zip.getInputStream(e).use { while (it.read(buf) >= 0) Unit }
        }
        null
    }.getOrElse { it.javaClass.simpleName + (it.message?.let { m -> ": " + m.take(120) } ?: "") }

    private fun refmap(jar: File): VerifyOutcome {
        corrupt(jar)?.let { return VerifyOutcome(Verdict.CONFIRMED, "static", "jar cannot be read: $it") }
        ZipFile(jar).use { zip ->
            fun text(name: String) = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }
            val names = linkedSetOf<String>()
            zip.getEntry("META-INF/MANIFEST.MF")?.let { e -> zip.getInputStream(e).use { java.util.jar.Manifest(it) } }
                ?.mainAttributes?.getValue("MixinConfigs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let(names::addAll)
            text("META-INF/mods.toml")?.let { t -> Regex("""config\s*=\s*"([^"]+)"""").findAll(t).forEach { names += it.groupValues[1] } }
            for (e in zip.entries()) if (!e.isDirectory && e.name.endsWith(".mixins.json") && !e.name.contains('/')) names += e.name
            val missing = mutableListOf<String>()
            val noKey = mutableListOf<String>()
            var ok = 0
            for (n in names) {
                val t = text(n) ?: continue
                val o = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() ?: continue
                val count = listOf("mixins", "client", "server").sumOf { k -> runCatching { o[k]?.jsonArray?.size ?: 0 }.getOrDefault(0) }
                if (count == 0) continue
                val rm = runCatching { o["refmap"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
                when {
                    rm == null -> noKey += n
                    zip.getEntry(rm) == null -> missing += "$n -> $rm"
                    else -> ok++
                }
            }
            return when {
                missing.isNotEmpty() -> VerifyOutcome(Verdict.CONFIRMED, "static", "refmap file missing: " + missing.joinToString(", "))
                noKey.isNotEmpty() -> VerifyOutcome(Verdict.INCONCLUSIVE, "static", "mixin config without refmap entry: " + noKey.joinToString(", ") + "; needs a launch test")
                ok > 0 -> VerifyOutcome(Verdict.REJECTED, "static", "all $ok mixin configs have their refmap")
                else -> VerifyOutcome(Verdict.REJECTED, "static", "no mixin configs with mixins")
            }
        }
    }
}

interface JarSource {
    suspend fun find(mod: ModRef, cacheDir: File): File?
}

class LocalSource(private val roots: List<File>) : JarSource {
    override suspend fun find(mod: ModRef, cacheDir: File): File? {
        val sha1 = mod.sha1 ?: return null
        for (root in roots) {
            val hit = root.walkTopDown().maxDepth(6)
                .filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled")) }
                .firstOrNull { DbRefs.sha1(it) == sha1 }
            if (hit != null) return hit
        }
        return null
    }
}

class ModrinthSource(private val http: Http) : JarSource {
    override suspend fun find(mod: ModRef, cacheDir: File): File? {
        val sha1 = mod.sha1 ?: return null
        val cached = File(cacheDir, "$sha1.jar")
        if (cached.isFile && DbRefs.sha1(cached) == sha1) return cached
        val body = runCatching { http.get("https://api.modrinth.com/v2/version_file/$sha1?algorithm=sha1") }.getOrNull() ?: return null
        val files = Json.parseToJsonElement(body).jsonObject["files"]?.jsonArray ?: return null
        val url = files.map { it.jsonObject }.firstOrNull { f -> f["hashes"]?.jsonObject?.get("sha1")?.jsonPrimitive?.content == sha1 }
            ?.get("url")?.jsonPrimitive?.content ?: return null
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "$sha1.part")
        http.download(url, tmp)
        if (DbRefs.sha1(tmp) != sha1) { tmp.delete(); return null }
        tmp.renameTo(cached)
        return cached
    }
}

class VerifyRunner(private val sources: List<JarSource>, private val verifiers: List<FileVerifier>, private val cacheDir: File) {
    suspend fun run(target: PendingFile, file: File? = null): VerifyOutcome {
        val verifier = verifiers.firstOrNull { it.supports(target.reason) }
            ?: return VerifyOutcome(Verdict.INCONCLUSIVE, "static", "no verifier for ${target.reason}; needs a launch test")
        val jar = file ?: sources.firstNotNullOfOrNull { runCatching { it.find(target.mod, cacheDir) }.getOrNull() }
            ?: return VerifyOutcome(Verdict.INCONCLUSIVE, "static", "file not found")
        if (target.mod.sha1 != null && DbRefs.sha1(jar) != target.mod.sha1) return VerifyOutcome(Verdict.INCONCLUSIVE, "static", "file hash does not match the report")
        return verifier.verify(target, jar)
    }
}

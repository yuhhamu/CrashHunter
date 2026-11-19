package dev.crashhunter.core.db

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.Finding
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.fix.remote.HttpStatusException
import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar
import kotlinx.serialization.json.Json
import java.io.File

class CrashDb(
    private val dir: File,
    private val http: Http?,
    private val endpoint: () -> String,
    private val token: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "type" }
    private val pretty = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true; classDiscriminator = "type" }
    private val outbox get() = File(dir, "outbox")
    private val localFile get() = File(dir, "local.jsonl")
    private fun snapFile(mc: String, loader: String) = File(dir, "snapshot-${safe(mc)}-${safe(loader)}.json")
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun encode(r: DbReport): String = json.encodeToString(DbReport.serializer(), r)
    fun preview(r: DbReport): String = pretty.encodeToString(DbReport.serializer(), r)
    fun decode(text: String): DbReport = json.decodeFromString(DbReport.serializer(), text)

    @Synchronized
    fun recordLocal(r: DbReport) {
        dir.mkdirs()
        localFile.appendText(encode(r) + "\n")
    }

    fun local(): List<DbReport> = if (!localFile.isFile) emptyList() else localFile.readLines().mapNotNull { runCatching { decode(it) }.getOrNull() }

    @Synchronized
    fun enqueue(r: DbReport): File {
        outbox.mkdirs()
        val f = File(outbox, "${r.time}-${(Math.random() * 1e6).toLong()}.json")
        f.writeText(encode(r))
        return f
    }

    fun pending(): List<File> = (outbox.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }

    suspend fun flush(): Int {
        val h = http ?: return 0
        var sent = 0
        for (f in pending()) {
            val ok = try {
                h.post("${endpoint().trimEnd('/')}/v1/reports", f.readText(), headers())
                true
            } catch (e: HttpStatusException) {
                if (e.status in 400..499 && e.status != 429) { reject(f, e); false } else return sent
            } catch (e: Exception) {
                return sent
            }
            if (ok) { f.delete(); sent++ }
        }
        return sent
    }

    private val runsOutbox get() = File(dir, "runs-outbox")

    val rejectedDir get() = File(dir, "rejected")

    private fun reject(f: File, e: HttpStatusException) {
        rejectedDir.mkdirs()
        runCatching {
            File(rejectedDir, f.name).writeText(f.readText())
            File(rejectedDir, f.name.removeSuffix(".json") + ".txt").writeText("${e.status} ${e.message}")
            rejectedDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        }
        f.delete()
    }

    @Synchronized
    fun enqueueRun(r: RunReport): File {
        runsOutbox.mkdirs()
        val f = File(runsOutbox, "${r.time}-${(Math.random() * 1e6).toLong()}.json")
        f.writeText(RunRecorder.encode(r))
        return f
    }

    fun pendingRuns(): List<File> = (runsOutbox.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }

    suspend fun flushRuns(): Int {
        val h = http ?: return 0
        var sent = 0
        for (f in pendingRuns()) {
            try {
                h.post("${endpoint().trimEnd('/')}/v1/runs", f.readText(), headers())
                f.delete(); sent++
            } catch (e: HttpStatusException) {
                if (e.status in 400..499 && e.status != 429) reject(f, e) else return sent
            } catch (e: Exception) {
                return sent
            }
        }
        return sent
    }

    suspend fun check(profile: GameProfile, mods: List<ModRef>): DbCheck {
        val h = http ?: error("offline")
        val env = DbRefs.env(profile) ?: error("unknown game version")
        val body = json.encodeToString(CheckRequest.serializer(), CheckRequest(env, mods))
        return json.decodeFromString(DbCheck.serializer(), h.post("${endpoint().trimEnd('/')}/v1/check", body, headers()))
    }

    @kotlinx.serialization.Serializable
    private data class CheckRequest(val env: DbEnv, val mods: List<ModRef>)

    suspend fun forgetMe(installId: String): Boolean {
        val h = http ?: return false
        val ok = runCatching { h.post("${endpoint().trimEnd('/')}/v1/forget", "{\"install\":\"$installId\"}", headers()); true }.getOrDefault(false)
        if (ok) (pending() + pendingRuns()).forEach { it.delete() }
        return ok
    }

    private fun headers(): Map<String, String> = token()?.let { mapOf("x-ch-token" to it) } ?: emptyMap()

    suspend fun refreshSnapshot(profile: GameProfile, maxAgeMs: Long = 3600_000L): DbSnapshot? {
        val env = DbRefs.env(profile) ?: return null
        val f = snapFile(env.mc, env.loader)
        val cached = snapshot(profile)
        if (cached != null && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return cached
        val h = http ?: return cached
        return try {
            val text = h.get("${endpoint().trimEnd('/')}/v1/snapshot?mc=${Http.enc(env.mc)}&loader=${Http.enc(env.loader)}", headers())
            val s = json.decodeFromString(DbSnapshot.serializer(), text)
            dir.mkdirs()
            f.writeText(text)
            s
        } catch (e: Exception) {
            cached
        }
    }

    fun snapshot(profile: GameProfile): DbSnapshot? {
        val env = DbRefs.env(profile) ?: return null
        val f = snapFile(env.mc, env.loader)
        return if (f.isFile) runCatching { json.decodeFromString(DbSnapshot.serializer(), f.readText()) }.getOrNull() else null
    }

    fun clearSnapshots() { dir.listFiles { f -> f.name.startsWith("snapshot-") }?.forEach { it.delete() } }

    companion object {
        fun knowledge(snap: DbSnapshot?, index: ModIndex): List<Finding> {
            snap ?: return emptyList()
            val enabled = index.jars.filter { it.enabled }
            val hashes = HashMap<ModJar, String?>()
            fun hash(j: ModJar) = hashes.getOrPut(j) { DbRefs.sha1(j.file) }
            fun find(ref: ModRef): ModJar? = enabled.firstOrNull { DbRefs.matches(ref, it) { hash(it) } > 0 }
            val out = mutableListOf<Finding>()
            for (b in snap.broken.sortedByDescending { it.w }) {
                val j = find(b.mod) ?: continue
                out += Finding(Cause.KNOWN_BROKEN, j.displayName, b.reason, actual = b.n.toString(), line = "db", jar = j.fileName)
            }
            for (f in snap.fixes.sortedByDescending { it.w }) {
                val from = f.from.singleOrNull() ?: continue
                val to = f.to.singleOrNull() ?: continue
                if (!from.id.equals(to.id, true) || to.v == null) continue
                val j = find(from) ?: continue
                if (j.mods.firstOrNull { it.modId.equals(to.id, true) }?.version == to.v) continue
                out += Finding(Cause.KNOWN_FIX, j.displayName, to.v, actual = f.n.toString(), line = "db", jar = j.fileName)
            }
            for (c in snap.conflicts.sortedByDescending { it.w }) {
                val jars = c.mods.map { find(it) ?: return@map null }
                if (jars.any { it == null } || jars.isEmpty()) continue
                val js = jars.filterNotNull()
                if (js.size == 1) { out += Finding(Cause.KNOWN_BROKEN, js[0].displayName, "CRASH", actual = c.n.toString(), line = "db", jar = js[0].fileName); continue }
                out += Finding(Cause.KNOWN_CONFLICT, js.first().displayName, js.drop(1).joinToString(", ") { it.displayName }.ifEmpty { null },
                    actual = c.n.toString(), line = "db", jar = js.first().fileName)
            }
            return out
        }

        fun suspects(snap: DbSnapshot?, index: ModIndex): List<String> =
            knowledge(snap, index).filter { it.cause == Cause.KNOWN_CONFLICT || it.cause == Cause.KNOWN_BROKEN }.mapNotNull { it.jar }.distinct()
    }
}

package dev.crashhunter.core.fix

import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.util.AppDirs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class FetchedFile(val file: File, val remote: RemoteFile)

data class FetchRules(val ranges: List<String> = emptyList(), val needRefmap: Boolean = false)

fun interface RemoteFetcher {
    suspend fun fetch(candidates: List<RemoteFile>, expectModId: String?, range: String?, instance: GameInstance, rules: FetchRules): FetchedFile
}

class FixApplier(
    private val fetcher: RemoteFetcher? = null,
    private val fixesDir: File = File(AppDirs.dataDir, "fixes"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; classDiscriminator = "type" }

    suspend fun apply(instance: GameInstance, proposals: List<FixProposal>, chainId: String? = null, round: Int = 1): FixRecord {
        val id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val dir = File(fixesDir, id).also { it.mkdirs() }
        val backups = File(dir, "backup").also { it.mkdirs() }
        val steps = mutableListOf<DoneStep>()
        val backedUp = mutableSetOf<String>()
        fun backupOnce(f: File) {
            if (!f.isFile || !backedUp.add(f.absolutePath)) return
            val b = File(backups, "${backedUp.size}-${f.name}")
            f.copyTo(b, overwrite = true)
            steps += FileBackedUp(f.absolutePath, b.absolutePath)
        }
        fun moveAside(f: File) {
            val b = File(backups, "${steps.size}-${f.name}")
            move(f, b)
            steps += FileMoved(f.absolutePath, b.absolutePath)
        }
        var record = FixRecord(id, instance.id, instance.dir.absolutePath, System.currentTimeMillis(), proposals, steps, chainId = chainId, round = round)
        try {
            for (p in proposals) for (a in p.actions) when (a) {
                is DisableMod -> {
                    val jar = File(instance.modsDir, a.file)
                    if (jar.isFile) {
                        val off = File(instance.modsDir, a.file + ".disabled")
                        move(jar, off)
                        steps += Renamed(jar.absolutePath, off.absolutePath)
                    }
                }
                is EnableMod -> {
                    val off = File(instance.modsDir, a.file)
                    val on = File(instance.modsDir, a.file.removeSuffix(".disabled"))
                    if (off.isFile && off != on && !on.exists()) {
                        move(off, on)
                        steps += Renamed(off.absolutePath, on.absolutePath)
                    }
                }
                is SetInstanceValue -> {
                    val format = dev.crashhunter.core.launcher.Formats.of(instance)
                    backupOnce(format.settingsFile(instance))
                    format.setValue(instance, a.key, a.value)
                }
                is SetComponentVersion -> {
                    val format = dev.crashhunter.core.launcher.Formats.of(instance)
                    backupOnce(format.componentsFile(instance))
                    format.setComponentVersion(instance, a.uid, a.version)
                }
                is LimitThreads -> {
                    val f = File(instance.gameDir, "config/fml.toml")
                    backupOnce(f)
                    if (!f.isFile) steps += FileAdded(f.absolutePath)
                    dev.crashhunter.core.bisect.LoadGuard.writeLimit(f)
                }
                is ResetConfig -> {
                    val f = File(instance.gameDir, a.path)
                    if (f.exists()) moveAside(f)
                }
                is AddMod -> {
                    val got = requireNotNull(fetcher) { "no fetcher" }.fetch(a.candidates, a.expectModId, a.range, instance, FetchRules())
                    val dest = SafeNames.inside(instance.modsDir, got.remote.fileName)
                    if (dest.exists()) moveAside(dest)
                    move(got.file, dest)
                    steps += FileAdded(dest.absolutePath)
                }
                is ReplaceMod -> {
                    val got = requireNotNull(fetcher) { "no fetcher" }.fetch(a.candidates, a.expectModId, a.range, instance, FetchRules(a.ranges, a.needRefmap))
                    val old = listOf(File(instance.modsDir, a.file), File(instance.modsDir, a.file + ".disabled")).firstOrNull { it.isFile }
                    if (old != null) moveAside(old)
                    val dest = SafeNames.inside(instance.modsDir, got.remote.fileName)
                    if (dest.exists()) moveAside(dest)
                    move(got.file, dest)
                    steps += FileAdded(dest.absolutePath)
                }
            }
        } catch (e: Throwable) {
            undoSteps(steps)
            record = record.copy(steps = steps.toList(), state = FixState.FAILED, note = e.message ?: e.javaClass.simpleName)
            save(record)
            throw e
        }
        record = record.copy(steps = steps.toList())
        save(record)
        return record
    }

    fun undo(record: FixRecord): FixRecord {
        undoSteps(record.steps)
        val r = record.copy(state = FixState.UNDONE)
        save(r)
        return r
    }

    fun mark(record: FixRecord, state: FixState, note: String = record.note): FixRecord = record.copy(state = state, note = note).also { save(it) }

    fun undoAll(records: List<FixRecord>): List<FixRecord> =
        records.sortedByDescending { it.round }.filter { it.state != FixState.UNDONE && it.state != FixState.FAILED }.map { undo(it) }

    fun recoverInterrupted(): List<FixRecord> =
        history().filter { it.state == FixState.VERIFYING }.map { r -> mark(undo(r), FixState.UNDONE, "interrupted") }

    fun history(): List<FixRecord> =
        (fixesDir.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedByDescending { it.name }
            .mapNotNull { d -> runCatching { json.decodeFromString(FixRecord.serializer(), File(d, "record.json").readText()) }.getOrNull() }

    private fun save(r: FixRecord) {
        val d = File(fixesDir, r.id).also { it.mkdirs() }
        File(d, "record.json").writeText(json.encodeToString(FixRecord.serializer(), r))
    }

    private fun undoSteps(steps: List<DoneStep>) {
        for (s in steps.reversed()) runCatching {
            when (s) {
                is Renamed -> { val to = File(s.to); if (to.exists()) move(to, File(s.from)) }
                is FileAdded -> File(s.path).delete()
                is FileMoved -> { val b = File(s.backup); if (b.exists()) move(b, File(s.from)) }
                is FileBackedUp -> File(s.backup).copyTo(File(s.path), overwrite = true)
            }
        }
    }

    companion object {
        fun move(from: File, to: File) {
            to.parentFile?.mkdirs()
            var last: Exception? = null
            repeat(10) {
                try {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (e: Exception) {
                    last = e
                    Thread.sleep(300)
                }
            }
            throw last ?: IllegalStateException("move failed")
        }

        private val packJson = Json { prettyPrint = true; prettyPrintIndent = "    " }

        fun withComponentVersion(text: String, uid: String, version: String): String {
            val root = Json.parseToJsonElement(text).jsonObject
            val comps = root["components"]?.jsonArray ?: error("no components")
            require(comps.any { it.jsonObject["uid"]?.jsonPrimitive?.contentOrNull == uid }) { "component $uid not found" }
            val changed = comps.map { c ->
                val o = c.jsonObject
                if (o["uid"]?.jsonPrimitive?.contentOrNull != uid) o
                else JsonObject(o.filterKeys { it !in setOf("cachedVersion", "cachedRequires", "cachedVolatile") } + ("version" to JsonPrimitive(version)))
            }
            return packJson.encodeToString(JsonObject.serializer(), JsonObject(root + ("components" to JsonArray(changed))))
        }
    }
}

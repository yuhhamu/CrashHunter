package dev.crashhunter.core.bisect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class ModToggler(private val modsDir: File, private val journalFile: File) {
    @Serializable
    data class Journal(
        val modsDir: String,
        val original: Map<String, Boolean>,
        val workCopy: Boolean,
        val instanceId: String,
        val cache: Map<String, TrialOutcome> = emptyMap(),
        val candidates: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun currentState(): Map<String, Boolean> =
        (modsDir.listFiles { f -> f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".jar.disabled")) } ?: emptyArray())
            .associate { it.name.removeSuffix(".disabled") to !it.name.endsWith(".disabled") }

    fun begin(instanceId: String, workCopy: Boolean): Journal {
        val j = Journal(modsDir.absolutePath, currentState(), workCopy, instanceId)
        save(j)
        return j
    }

    fun save(j: Journal) {
        journalFile.parentFile?.mkdirs()
        val tmp = File(journalFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Journal.serializer(), j))
        if (!tmp.renameTo(journalFile)) { journalFile.delete(); tmp.renameTo(journalFile) }
    }

    fun apply(candidates: Collection<String>, enabled: Set<String>) {
        for (name in candidates) set(name, name in enabled)
    }

    fun set(name: String, on: Boolean) {
        val jar = File(modsDir, name)
        val off = File(modsDir, "$name.disabled")
        val (from, to) = if (on) off to jar else jar to off
        if (!from.exists()) return
        repeat(20) {
            if (to.exists()) return
            if (from.renameTo(to)) return
            Thread.sleep(500)
        }
        error("Could not rename ${from.name} (file in use?)")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(journalFile: File): Journal? =
            runCatching { json.decodeFromString(Journal.serializer(), journalFile.readText()) }.getOrNull()

        fun restore(journalFile: File): Int {
            val j = read(journalFile) ?: run { journalFile.delete(); return 0 }
            val t = ModToggler(File(j.modsDir), journalFile)
            val now = t.currentState()
            var changed = 0
            for ((name, on) in j.original) if (now[name] != null && now[name] != on) { t.set(name, on); changed++ }
            journalFile.delete()
            return changed
        }
    }
}

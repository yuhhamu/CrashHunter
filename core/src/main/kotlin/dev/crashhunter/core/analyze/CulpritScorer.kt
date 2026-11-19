package dev.crashhunter.core.analyze

import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar
import dev.crashhunter.core.mods.PLATFORM_IDS

object CulpritScorer {
    private class Acc(val key: String, var jar: ModJar?, var modId: String?, var fileName: String?) {
        var score = 0
        val evidence = mutableListOf<Evidence>()
        fun add(points: Int, e: Evidence) { score += points; if (evidence.none { it == e }) evidence += e }
    }

    private val platformJar = Regex("""^(neo)?forge-|^minecraft|^client|^server|^fml|^javafml|^lowcode|^mclanguage|^modlauncher|^securejarhandler|^bootstraplauncher|^mixin|^intermediary|^fabric-loader|^named|^quilt-loader|^neoform|^loader-""")

    fun score(parsed: ParsedCrash, findings: List<Finding>, index: ModIndex?): List<Suspect> {
        val accs = LinkedHashMap<String, Acc>()

        fun resolveId(modId: String): Acc? {
            val id = modId.lowercase()
            if (id in PLATFORM_IDS) return null
            val jar = index?.byModId(id)
            val file = jar?.fileName ?: parsed.modFiles[modId]?.let { if (index != null) index.byFileName(it)?.fileName ?: it else it }
            val key = file?.lowercase() ?: "id:$id"
            return accs.getOrPut(key) { Acc(key, jar ?: file?.let { index?.byFileName(it) }, modId, file) }
        }
        fun resolveFile(name: String): Acc? {
            val jar = index?.byFileName(name)
            if (jar == null && platformJar.containsMatchIn(name.lowercase())) return null
            val file = jar?.fileName ?: name
            val key = file.lowercase()
            return accs.getOrPut(key) { Acc(key, jar, jar?.primaryId, file) }
        }
        fun resolveFrame(f: StackFrame): Acc? {
            f.modHint?.let { if (it !in PLATFORM_IDS) return resolveId(it) }
            f.jarHint?.let { resolveFile(it)?.let { a -> return a } }
            if (index != null) {
                (index.byClass(f.className) ?: index.byMixinPackage(f.className))?.let { j ->
                    return accs.getOrPut(j.fileName.lowercase()) { Acc(j.fileName.lowercase(), j, j.primaryId, j.fileName) }
                }
            }
            return null
        }

        for (fd in findings) {
            fd.mod?.let { m -> resolveId(m)?.add(120, Evidence(EvidenceKind.LOADER_REPORT, fd.line.ifBlank { fd.cause.name })) }
            if (fd.cause == Cause.DUPLICATE_MOD) {
                fd.other?.split(',', ';')?.map { it.trim() }?.filter { it.endsWith(".jar") }?.forEach { resolveFile(it)?.add(40, Evidence(EvidenceKind.DUPLICATE, it)) }
            }
            if (fd.cause == Cause.CORRUPT_JAR || fd.cause == Cause.WRONG_LOADER) {
                fd.other?.takeIf { it.endsWith(".jar") }?.let { resolveFile(it)?.add(100, Evidence(EvidenceKind.LOADER_REPORT, fd.line)) }
            }
        }
        for (mf in parsed.modFailures) {
            resolveId(mf.modId)?.add(100, Evidence(EvidenceKind.MOD_FAILURE, mf.message.lineSequence().first()))
        }
        parsed.duplicates.forEach { (id, files) -> files.forEach { f -> resolveFile(f)?.add(40, Evidence(EvidenceKind.DUPLICATE, "$id: $f")) } }
        parsed.suspectedMods.forEachIndexed { i, id -> resolveId(id)?.add(maxOf(20, 70 - 15 * i), Evidence(EvidenceKind.SUSPECTED_BY_REPORT, id)) }
        parsed.mixinMods.forEach { id -> resolveId(id)?.add(35, Evidence(EvidenceKind.MIXIN, id)) }

        var rank = 0
        val seen = HashSet<String>()
        for (f in parsed.frames) {
            if (StackTraceTools.isPlatformClass(f.className) && f.modHint == null) continue
            val a = resolveFrame(f) ?: continue
            if (!seen.add(a.key)) continue
            val pts = when (rank) { 0 -> 60; 1 -> 25; 2 -> 15; else -> 8 }
            a.add(pts, Evidence(if (rank == 0) EvidenceKind.TOP_FRAME else EvidenceKind.FRAME, f.toString()))
            rank++
            if (rank >= 6) break
        }

        return accs.values.filter { it.score > 0 }
            .sortedByDescending { it.score }
            .map { a ->
                val name = a.jar?.displayName ?: a.modId ?: a.fileName ?: a.key
                Suspect(a.jar?.fileName ?: a.fileName, a.jar?.primaryId ?: a.modId, name, a.score, a.evidence.toList())
            }
            .take(8)
    }
}

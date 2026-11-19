package dev.crashhunter.core.db

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.analyze.StackTraceTools
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar

object DbDetails {
    private const val MAX_FRAMES = 24
    private val skipCauses = setOf(Cause.KNOWN_BROKEN, Cause.KNOWN_CONFLICT, Cause.KNOWN_FIX, Cause.NO_PROBLEM)

    fun crash(d: Diagnosis, index: ModIndex, context: String, seen: Int? = null): CrashItem? {
        if (d.cause == Cause.NO_PROBLEM) return null
        val p = d.parsed
        val source = when (d.source.kind) {
            SourceKind.CRASH_REPORT -> "crash-report"
            SourceKind.HS_ERR -> "hs_err"
            SourceKind.LATEST_LOG -> "log"
            SourceKind.MODS_CHECK -> return null
        }
        val refs = LinkedHashMap<String, ModRef>()
        fun add(jar: ModJar?) { if (jar != null && refs.size < 12) refs.getOrPut(jar.fileName) { DbRefs.of(jar) } }

        val frames = p?.frames.orEmpty().take(MAX_FRAMES).map { f ->
            val jar = f.jarHint?.let { index.byFileName(it) } ?: f.modHint?.let { index.byModId(it) }
                ?: if (StackTraceTools.isPlatformClass(f.className)) null else index.byClass(f.className) ?: index.byMixinPackage(f.className)
            val modId = jar?.primaryId ?: f.modHint?.takeIf { it !in platformIds }
            if (jar != null && jar.primaryId !in platformIds) add(jar)
            FrameRef(f.className.take(200), f.method.take(120), f.src?.take(80), modId?.take(64))
        }
        for (s in d.suspects.take(5)) add(s.jar?.let { index.byFileName(it) } ?: s.modId?.let { index.byModId(it) })
        for (id in p?.mixinMods.orEmpty() + p?.suspectedMods.orEmpty()) add(index.byModId(id))

        val findings = d.findings.filter { it.cause !in skipCauses }.take(12).map { f ->
            val jar = f.jar?.let { index.byFileName(it) }
            FindingRef(
                f.cause.name,
                (jar?.primaryId ?: f.mod)?.let(::clip),
                f.other?.let(::clip),
                f.required?.let(::clip),
                f.actual?.let(::clip),
                f.config?.substringAfterLast('/')?.let(::clip),
                f.line.takeIf { it.isNotBlank() }?.let { StackTraceTools.stripPaths(it).take(300) },
            )
        }
        for (f in d.findings.filter { it.cause !in skipCauses }) add(f.jar?.let { index.byFileName(it) })

        return CrashItem(
            context = context,
            source = source,
            cause = d.cause.name,
            fp = DbRefs.fingerprint(d.fingerprint),
            ex = (p?.exceptionType ?: p?.nativeFrame?.let { "native" })?.take(200),
            msg = p?.message?.let { StackTraceTools.stripPaths(it).take(300) }?.ifBlank { null },
            phase = p?.description?.takeIf { d.source.kind == SourceKind.CRASH_REPORT }?.let { StackTraceTools.stripPaths(it).take(120) },
            causes = p?.causes.orEmpty().map { (ex, msg) -> ExRef(ex.take(200), msg?.let { StackTraceTools.stripPaths(it).take(300) }) },
            frames = frames,
            mods = refs.values.toList(),
            findings = findings,
            sys = p?.system.orEmpty(),
            heapMb = p?.maxHeapMb,
            seen = seen,
        )
    }

    fun dependencies(culprits: List<ModJar>, requirements: List<ModJar>): List<DependencyItem> {
        val out = mutableListOf<DependencyItem>()
        for (c in culprits) for (r in requirements) {
            if (c.fileName == r.fileName) continue
            val ids = r.mods.map { it.modId.lowercase() }.toSet()
            val decl = c.mods.flatMap { it.dependencies }.firstOrNull { it.modId.lowercase() in ids }
            out += DependencyItem(DbRefs.of(c), DbRefs.of(r), decl != null, decl?.range?.takeIf { it != "*" }?.take(64))
            if (out.size >= 8) return out
        }
        return out
    }

    fun compatible(index: ModIndex): CompatibleItem? {
        val mods = index.jars.filter { it.enabled }.map { DbRefs.of(it) }
        return if (mods.size >= 2) CompatibleItem(mods.take(2000)) else null
    }

    private val platformIds = setOf("minecraft", "forge", "neoforge", "fml", "fabricloader", "java", "mixinextras")

    private fun clip(s: String) = StackTraceTools.stripPaths(s).take(160)
}

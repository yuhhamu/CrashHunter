package dev.crashhunter.core.analyze

import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex
import java.io.File
import kotlin.math.abs

class DiagnosisService(private val kb: SolutionKb) {
    @Volatile var knowledge: ((ModIndex, dev.crashhunter.core.model.GameProfile) -> List<Finding>)? = null

    fun sources(instance: GameInstance, limit: Int = 30): List<CrashSource> {
        val g = instance.gameDir
        val list = mutableListOf<CrashSource>()
        File(g, "crash-reports").listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { list += CrashSource(SourceKind.CRASH_REPORT, it, it.lastModified()) }
        g.listFiles { f -> f.isFile && f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }?.forEach { list += CrashSource(SourceKind.HS_ERR, it, it.lastModified()) }
        instance.dir.listFiles { f -> f.isFile && f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }?.forEach { list += CrashSource(SourceKind.HS_ERR, it, it.lastModified()) }
        val sorted = list.sortedByDescending { it.timeMs }.take(limit).toMutableList()
        File(g, "logs/latest.log").takeIf { it.isFile }?.let { sorted.add(0, CrashSource(SourceKind.LATEST_LOG, it, it.lastModified())) }
        sorted.add(0, CrashSource(SourceKind.MODS_CHECK, instance.modsDir, System.currentTimeMillis()))
        return sorted
    }

    fun parse(source: CrashSource): ParsedCrash? {
        val f = source.file ?: return null
        if (!f.isFile) return null
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrElse { runCatching { f.readText(Charsets.ISO_8859_1) }.getOrNull() } ?: return null
        return when (source.kind) {
            SourceKind.CRASH_REPORT -> CrashReportParser.parse(text, f)
            SourceKind.HS_ERR -> HsErrParser.parse(text, f)
            SourceKind.LATEST_LOG -> LatestLogParser.parse(text, f)
            SourceKind.MODS_CHECK -> null
        }
    }

    fun diagnose(source: CrashSource, instance: GameInstance?, index: ModIndex?): Diagnosis {
        if (source.kind == SourceKind.MODS_CHECK) return diagnoseMods(source, instance, index)
        val parsed = parse(source) ?: return fromFindings(source, emptyList(), emptyList(), null, instance)
        val extra = if (source.kind != SourceKind.LATEST_LOG && instance != null) {
            val log = File(instance.gameDir, "logs/latest.log")
            if (log.isFile && abs(log.lastModified() - source.timeMs) < 120_000) LaunchCrashDetector.readHead(log)?.let { LogScanner.scan(it) } ?: emptyList() else emptyList()
        } else emptyList()
        val findings = CauseClassifier.sort(refine(CauseClassifier.classify(parsed, extra), index))
        val suspects = CulpritScorer.score(parsed, findings, index)
        return fromFindings(source, findings, suspects, parsed, instance)
    }

    fun diagnoseText(text: String, file: File? = null, index: ModIndex? = null): Diagnosis {
        val kind = when {
            text.contains("A fatal error has been detected by the Java Runtime Environment") -> SourceKind.HS_ERR
            text.contains("---- Minecraft Crash Report ----") -> SourceKind.CRASH_REPORT
            else -> SourceKind.LATEST_LOG
        }
        val src = CrashSource(kind, file, file?.lastModified() ?: System.currentTimeMillis())
        val parsed = when (kind) {
            SourceKind.HS_ERR -> HsErrParser.parse(text, file)
            SourceKind.CRASH_REPORT -> CrashReportParser.parse(text, file)
            else -> LatestLogParser.parse(text, file)
        }
        val findings = CauseClassifier.sort(refine(CauseClassifier.classify(parsed), index))
        return fromFindings(src, findings, CulpritScorer.score(parsed, findings, index), parsed, null)
    }

    private fun diagnoseMods(source: CrashSource, instance: GameInstance?, index: ModIndex?): Diagnosis {
        if (instance == null || index == null) return fromFindings(source, emptyList(), emptyList(), null, instance)
        val findings = StaticChecker.check(index, instance.profile) + (runCatching { knowledge?.invoke(index, instance.profile) }.getOrNull() ?: emptyList())
        val suspects = findings.mapNotNull { f ->
            val jar = f.mod?.let { m -> index.jars.firstOrNull { it.displayName == m } }
            jar?.let { Suspect(it.fileName, it.primaryId, it.displayName, 100, listOf(Evidence(EvidenceKind.STATIC_CHECK, f.cause.name))) }
        }.distinctBy { it.jar }
        return fromFindings(source, findings, suspects, null, instance)
    }

    private fun refine(findings: List<Finding>, index: ModIndex?): List<Finding> {
        if (index == null) return findings
        return findings.map { f ->
            if (f.config != null && (f.cause == Cause.MIXIN_FAILURE || f.cause == Cause.MIXIN_NO_REFMAP)) {
                val owner = index.byMixinConfig(f.config) ?: return@map f
                val noRefmap = f.cause == Cause.MIXIN_NO_REFMAP || owner.missingRefmaps.any { it.substringAfterLast('/').equals(f.config.substringAfterLast('/'), true) }
                return@map f.copy(cause = if (noRefmap) Cause.MIXIN_NO_REFMAP else f.cause, mod = owner.primaryId ?: f.mod, jar = owner.fileName)
            }
            if (f.cause == Cause.MISSING_DEPENDENCY && f.other != null) {
                val provider = index.byModId(f.other)
                if (provider != null && provider.enabled) {
                    val v = provider.mods.firstOrNull { it.modId.equals(f.other, true) }?.version
                    return@map f.copy(cause = Cause.DEPENDENCY_VERSION, actual = f.actual ?: v)
                }
            }
            f
        }
    }

    fun fromFindings(source: CrashSource, findings: List<Finding>, suspects: List<Suspect>, parsed: ParsedCrash?, instance: GameInstance?): Diagnosis {
        val nothing = findings.isEmpty() && suspects.isEmpty() &&
            (source.kind == SourceKind.MODS_CHECK || (source.kind == SourceKind.LATEST_LOG && parsed?.effectiveException == null))
        if (nothing) {
            val r = kb.render(Cause.NO_PROBLEM, emptyMap())
            return Diagnosis(source, Cause.NO_PROBLEM, emptyList(), emptyList(), r.title, r.detail, emptyList(), null, parsed)
        }
        val primary = CauseClassifier.primary(findings, suspects, parsed)
        val modName = primary.mod?.let { m -> suspects.firstOrNull { it.modId.equals(m, true) || it.name == m }?.name ?: m }
            ?: suspects.firstOrNull()?.name
        val params = mapOf(
            "mod" to modName,
            "other" to (primary.other ?: parsed?.effectiveMessage?.take(80)),
            "required" to RangeText.humanize(primary.required, kb.lang),
            "actual" to primary.actual,
            "mc" to (instance?.profile?.mcVersion ?: parsed?.mcVersion),
            "loader" to instance?.profile?.loader?.display,
        )
        val r = kb.render(primary.cause, params)
        val others = findings.filter { it != primary && !(it.cause == primary.cause && it.mod == primary.mod) }.take(10).map { f ->
            val rr = kb.render(f.cause, mapOf("mod" to f.mod, "other" to f.other, "required" to RangeText.humanize(f.required, kb.lang), "actual" to f.actual,
                "mc" to instance?.profile?.mcVersion, "loader" to instance?.profile?.loader?.display))
            "${rr.title}: ${rr.detail}"
        }
        val fp = parsed?.let { StackTraceTools.fingerprint(it, primary.cause.name) }
        return Diagnosis(source, primary.cause, findings, suspects, r.title, r.detail, r.solutions.take(3), fp, parsed, others, r.afterBisect.take(3))
    }
}

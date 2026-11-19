package dev.crashhunter.core.analyze

object CauseClassifier {
    private val order = listOf(
        Cause.LOADER_VERSION, Cause.MC_VERSION, Cause.JAVA_VERSION, Cause.MISSING_DEPENDENCY, Cause.DEPENDENCY_VERSION,
        Cause.WRONG_LOADER, Cause.DUPLICATE_MOD, Cause.CORRUPT_JAR, Cause.INCOMPATIBLE_MOD, Cause.CLIENT_ONLY_ON_SERVER,
        Cause.MIXIN_NO_REFMAP, Cause.MIXIN_FAILURE, Cause.CONFIG_ERROR, Cause.OUT_OF_MEMORY, Cause.GPU_DRIVER, Cause.WATCHDOG_HANG,
    )
    private val classMismatch = Regex("""(NoSuchFieldError|NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|AbstractMethodError|IncompatibleClassChangeError|ClassCastException.*cannot be cast)(?::\s*(\S+))?""")

    fun classify(p: ParsedCrash, extraFindings: List<LogFinding> = emptyList()): List<Finding> {
        val out = mutableListOf<Finding>()
        val all = (p.logFindings + extraFindings).distinctBy { "${it.kind}|${it.mod}|${it.other}|${it.config}" }
        val noRefmap = all.filter { it.kind == FindingKind.MIXIN_NO_REFMAP }.mapNotNull { it.config?.lowercase() }.toSet()
        val failedConfigs = all.filter { it.kind == FindingKind.MIXIN_FAILURE }.mapNotNull { it.config?.lowercase() }.toSet()
        val targets = all.filter { it.kind == FindingKind.MIXIN_FAILURE && it.config != null && it.other != null }.associate { it.config!!.lowercase() to it.other }
        val logFindings = all.filter { it.kind != FindingKind.MIXIN_NO_REFMAP || it.config?.lowercase() in failedConfigs }
            .map { if (it.kind == FindingKind.MIXIN_NO_REFMAP) it.copy(other = targets[it.config!!.lowercase()] ?: it.other) else it }
            .filter { it.kind != FindingKind.MIXIN_FAILURE || it.config?.lowercase() !in noRefmap }
        for (lf in mergeMixin(logFindings)) {
            out += Finding(Cause.of(lf.kind), lf.mod ?: lf.config?.let(::configStem), lf.other, lf.required, lf.actual ?: currentVersion(lf, p), lf.line, config = lf.config)
        }
        p.duplicates.forEach { (id, files) -> out += Finding(Cause.DUPLICATE_MOD, id, files.joinToString(", ")) }

        for (mf in p.modFailures) {
            val text = listOfNotNull(mf.exception, mf.message).joinToString("\n")
            classMismatch.find(text)?.let { out += Finding(Cause.CLASS_MISMATCH, mf.modId, clean(it.groupValues[2]).ifEmpty { it.groupValues[1] }, line = it.value) }
        }
        val exc = p.allText
        classMismatch.find(exc)?.let { m -> if (out.none { it.cause == Cause.CLASS_MISMATCH }) out += Finding(Cause.CLASS_MISMATCH, null, clean(m.groupValues[2]).ifEmpty { m.groupValues[1] }, line = m.value) }

        if (p.source.kind == SourceKind.HS_ERR && out.none { it.cause == Cause.GPU_DRIVER || it.cause == Cause.OUT_OF_MEMORY }) {
            out += Finding(Cause.NATIVE_CRASH, null, p.nativeFrame ?: p.exceptionType, line = p.message ?: "")
        }
        if (p.modFailures.isNotEmpty() && out.none { it.mod != null }) {
            val mf = p.modFailures.first()
            out += Finding(Cause.MOD_LOAD_ERROR, mf.modId, mf.exception ?: mf.message.lineSequence().firstOrNull(), line = mf.message)
        }
        return sort(out)
    }

    fun sort(list: List<Finding>): List<Finding> =
        list.distinctBy { "${it.cause}|${it.mod}|${it.other}" }.sortedBy { f -> order.indexOf(f.cause).let { if (it < 0) order.size else it } }

    fun configStem(config: String): String = config.substringBefore('.').substringAfterLast('/')

    private fun mergeMixin(list: List<LogFinding>): List<LogFinding> {
        val (mixin, rest) = list.partition { it.kind == FindingKind.MIXIN_FAILURE && it.config != null && it.mod == null }
        val merged = mixin.groupBy { it.config!!.lowercase() }.map { (_, g) -> g.firstOrNull { it.other != null } ?: g.first() }
        return rest + merged
    }

    private fun clean(s: String) = s.trim('\'', '"', ',', '.', ';', ':', '(', ')').replace('/', '.')

    private fun currentVersion(lf: LogFinding, p: ParsedCrash): String? {
        val dep = lf.other ?: return null
        val failureText = p.modFailures.joinToString("\n") { it.message }
        if (Regex("""Currently, ${Regex.escape(dep)} is not installed""", RegexOption.IGNORE_CASE).containsMatchIn(failureText)) return null
        return Regex("""Currently, ${Regex.escape(dep)} is ([^\s]+)""", RegexOption.IGNORE_CASE).find(failureText)?.groupValues?.get(1)
    }

    fun primary(findings: List<Finding>, suspects: List<Suspect>, p: ParsedCrash?): Finding {
        findings.firstOrNull()?.let { f ->
            if (f.cause == Cause.MISSING_DEPENDENCY && f.actual != null) return f.copy(cause = Cause.DEPENDENCY_VERSION)
            return f
        }
        val top = suspects.firstOrNull()
        if (top != null && p != null) {
            val ex = p.effectiveException?.substringAfterLast('.')
            return Finding(Cause.MOD_RUNTIME_ERROR, top.modId ?: top.name, ex ?: p.description)
        }
        return Finding(Cause.UNKNOWN, null, p?.effectiveException ?: p?.description)
    }
}

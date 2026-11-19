package dev.crashhunter.core.analyze

import java.io.File

object HsErrParser {
    fun parse(text: String, file: File? = null, timeMs: Long = file?.lastModified() ?: 0L): ParsedCrash {
        val lines = text.lines()
        val signal = lines.firstOrNull { it.startsWith("#  ") && it.contains(" at pc=") }?.removePrefix("#")?.trim()?.substringBefore(" at pc=")
        val pfIdx = lines.indexOfFirst { it.startsWith("# Problematic frame:") }
        val problematic = if (pfIdx >= 0) lines.getOrNull(pfIdx + 1)?.removePrefix("#")?.trim() else null
        val nativeLib = problematic?.let { Regex("""\[([^+\]]+)""").find(it)?.groupValues?.get(1) }
        val oomLine = lines.firstOrNull { it.contains("Out of Memory Error") || it.contains("There is insufficient memory") || it.contains("Native memory allocation") }
            ?.removePrefix("#")?.trim()
        val jre = lines.firstOrNull { it.startsWith("# JRE version:") }?.substringAfter(":")?.trim()

        val frames = mutableListOf<StackFrame>()
        val frameRegex = Regex("""^[jJ]\s+(?:\d+\s+(?:c1|c2|jvmci)?\s*)?([\w.$/]+)\.([\w$<>]+)\(""")
        for (l in lines) {
            val m = frameRegex.find(l) ?: continue
            frames += StackFrame(m.groupValues[1].replace('/', '.'), m.groupValues[2])
            if (frames.size >= 40) break
        }

        val findings = mutableListOf<LogFinding>()
        if (oomLine != null) findings += LogFinding(FindingKind.OUT_OF_MEMORY, other = oomLine, line = oomLine)
        if (nativeLib != null && isGpuLibrary(nativeLib)) findings += LogFinding(FindingKind.GPU_DRIVER, other = nativeLib, line = problematic)

        val excerpt = lines.take(14).joinToString("\n")
        return ParsedCrash(
            source = CrashSource(SourceKind.HS_ERR, file, timeMs),
            description = "JVM crash (hs_err)",
            exceptionType = signal ?: oomLine?.let { "OutOfMemoryError" },
            message = oomLine ?: problematic,
            frames = frames,
            javaVersion = jre,
            nativeFrame = nativeLib ?: problematic,
            logFindings = findings,
            excerpt = excerpt,
        )
    }

    fun isGpuLibrary(lib: String): Boolean {
        val l = lib.lowercase()
        return listOf("atio6axx", "atioglxx", "atig6pxx", "amdxc", "ig75icd", "ig9icd", "ig8icd", "igxelpicd", "ig11icd", "ig12icd", "nvoglv", "nvd3dum", "opengl32", "libgl", "radeonsi", "iris_dri", "i965_dri", "nouveau")
            .any { l.contains(it) }
    }
}

object LatestLogParser {
    fun parse(text: String, file: File? = null, timeMs: Long = file?.lastModified() ?: 0L): ParsedCrash {
        val findings = LogScanner.scan(text)
        val lines = text.lines()
        var excIdx = -1
        for (i in lines.indices.reversed()) {
            val l = lines[i].trim()
            if (Regex("""^([\w.$]+(?:Exception|Error))(?::.*)?$""").matches(l) && lines.getOrNull(i + 1)?.trim()?.startsWith("at ") == true) { excIdx = i; break }
        }
        val block = if (excIdx >= 0) lines.subList(excIdx, minOf(lines.size, excIdx + 80)).joinToString("\n") else ""
        val parsed = if (block.isNotEmpty()) CrashReportParser.parse("Description: latest.log\n\n$block") else null
        return ParsedCrash(
            source = CrashSource(SourceKind.LATEST_LOG, file, timeMs),
            description = findings.firstOrNull()?.line ?: parsed?.exceptionType,
            exceptionType = parsed?.exceptionType,
            message = parsed?.message,
            rootCause = parsed?.rootCause,
            rootMessage = parsed?.rootMessage,
            frames = parsed?.frames ?: emptyList(),
            suspectedMods = parsed?.suspectedMods ?: emptyList(),
            mixinMods = (parsed?.mixinMods ?: emptyList()) + findings.filter { it.kind == FindingKind.MIXIN_FAILURE }.mapNotNull { it.mod },
            logFindings = findings,
            excerpt = (findings.take(6).map { it.line } + block.lines().take(12)).joinToString("\n"),
        )
    }
}

object LaunchCrashDetector {
    private const val LOG_SCAN_BYTES = 4 * 1024 * 1024
    private val FATAL = Regex(
        """Loading errors encountered|Mixin apply for mod \S+ failed|MixinApplyError|Mod loading has failed|Mod Loading has failed|""" +
            """Encountered an unexpected exception|Exception in thread "main"|This crash report has been saved to|""" +
            """Incompatible mods found!|Missing or unsupported mandatory dependencies|Failed to start the minecraft server|""" +
            """Preparing crash report with UUID|#@!@# Game crashed!""",
    )

    data class Crash(val reason: String, val file: File?, val kind: SourceKind)

    fun newestAfter(dir: File, sinceMs: Long, accept: (File) -> Boolean): File? =
        dir.listFiles { f -> f.isFile && accept(f) }?.filter { it.lastModified() >= sinceMs }?.maxByOrNull { it.lastModified() }

    fun detect(gameDir: File, sinceMs: Long): Crash? {
        newestAfter(File(gameDir, "crash-reports"), sinceMs) { it.name.endsWith(".txt") }?.let { return Crash("crash report", it, SourceKind.CRASH_REPORT) }
        newestAfter(gameDir, sinceMs) { it.name.startsWith("hs_err_pid") && it.name.endsWith(".log") }?.let { return Crash("hs_err", it, SourceKind.HS_ERR) }
        val log = File(gameDir, "logs/latest.log")
        if (!log.isFile || log.lastModified() < sinceMs) return null
        val text = readHead(log) ?: return null
        val hit = FATAL.find(text) ?: return null
        return Crash(hit.value, log, SourceKind.LATEST_LOG)
    }

    fun readHead(log: File): String? = runCatching {
        val size = log.length()
        if (size <= LOG_SCAN_BYTES * 2L) return@runCatching log.readBytes().toString(Charsets.UTF_8)
        java.io.RandomAccessFile(log, "r").use { f ->
            val head = ByteArray(LOG_SCAN_BYTES).also { f.readFully(it) }
            f.seek(size - LOG_SCAN_BYTES)
            val tail = ByteArray(LOG_SCAN_BYTES).also { f.readFully(it) }
            head.toString(Charsets.UTF_8) + "\n" + tail.toString(Charsets.UTF_8)
        }
    }.getOrNull()
}

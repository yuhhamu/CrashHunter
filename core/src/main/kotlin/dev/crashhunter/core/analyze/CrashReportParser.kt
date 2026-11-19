package dev.crashhunter.core.analyze

import java.io.File

object CrashReportParser {
    private val exceptionLine = Regex("""^([a-zA-Z_][\w$]*(?:\.[\w$]+)+(?:Exception|Error|Throwable)[\w$]*|[a-zA-Z_][\w$]*(?:\.[\w$]+)*\$\w*(?:Exception|Error))(?::\s*(.*))?$""")
    private val causedBy = Regex("""^Caused by:\s*([\w.$]+)(?::\s*(.*))?$""")
    private val frameLine = Regex("""^\s*at\s+(?:([^\s(]+?)/)?([\w.$]+)\.([\w$<>-]+)\(([^)]*)\)(.*)$""")
    private val mixinMethod = Regex("""(?:handler|redirect|modify\w*|wrapOperation|wrapWithCondition|localvar|inject|md)\$\w+\$([a-z0-9_\-]{2,64})\$""")
    private val sectionHeader = Regex("""^-- (.+) --$""")

    fun parse(text: String, file: File? = null, timeMs: Long = file?.lastModified() ?: 0L): ParsedCrash {
        val lines = text.lines()
        val description = lines.firstOrNull { it.startsWith("Description:") }?.substringAfter(":")?.trim()

        val start = lines.indexOfFirst { it.startsWith("Description:") }.let { if (it < 0) 0 else it + 1 }
        var exceptionType: String? = null
        var message: String? = null
        for (i in start until lines.size) {
            val l = lines[i].trim()
            if (l.isEmpty()) continue
            if (l.startsWith("A detailed walkthrough")) break
            val m = exceptionLine.matchEntire(l) ?: continue
            exceptionType = m.groupValues[1]
            message = m.groupValues[2].ifBlank { null }
            break
        }
        var rootCause: String? = null
        var rootMessage: String? = null
        val causes = mutableListOf<Pair<String, String?>>()
        val stackEnd = lines.indexOfFirst { it.startsWith("A detailed walkthrough") }.let { if (it < 0) lines.size else it }
        for ((i, l) in lines.withIndex()) {
            val m = causedBy.matchEntire(l.trim()) ?: continue
            rootCause = m.groupValues[1]; rootMessage = m.groupValues[2].ifBlank { null }
            if (i < stackEnd && causes.none { it.first == rootCause && it.second == rootMessage }) causes += rootCause!! to rootMessage
        }

        val frames = mutableListOf<StackFrame>()
        val mixinMods = linkedSetOf<String>()
        for (l in lines) {
            val m = frameLine.matchEntire(l) ?: continue
            val module = m.groupValues[1]
            val cls = m.groupValues[2]
            val method = m.groupValues[3]
            val tail = m.groupValues[5]
            val src = m.groupValues[4].takeIf { Regex("""^[\w$.\-]+\.(?:java|kt|scala|groovy)(?::\d+)?$""").matches(it) }
            val modHint = Regex("""^TRANSFORMER/([a-z0-9_\-]+)@""").find(module + "/")?.groupValues?.get(1)
                ?: Regex("""^([a-z][a-z0-9_\-]+)@""").find(module)?.groupValues?.get(1)
            val jarHint = Regex("""\[([^\]\s%!:]+\.jar)""").find(tail)?.groupValues?.get(1)
            mixinMethod.find(method)?.let { mixinMods += it.groupValues[1] }
            frames += StackFrame(cls, method, jarHint, modHint, src)
        }

        val (failures, sectionMods) = parseSections(lines)
        val duplicates = parseLegacyDuplicates(lines)
        val modFiles = parseModList(lines)

        fun detail(key: String) = lines.firstOrNull { it.trim().startsWith("$key:") }?.substringAfter(":")?.trim()?.ifBlank { null }
        val heap = Regex("""-Xmx(\d+)([MmGg])""").find(text)?.let { val n = it.groupValues[1].toLong(); if (it.groupValues[2].equals("g", true)) n * 1024 else n }
            ?: Regex("""up to \d+ bytes \((\d+) Mi?B\)""").find(text)?.groupValues?.get(1)?.toLongOrNull()

        val excerptStart = lines.indexOfFirst { it.startsWith("Description:") }
        val excerpt = if (excerptStart >= 0) lines.drop(excerptStart).take(18).joinToString("\n") else lines.take(18).joinToString("\n")

        return ParsedCrash(
            source = CrashSource(SourceKind.CRASH_REPORT, file, timeMs),
            description = description,
            exceptionType = exceptionType,
            message = message,
            rootCause = rootCause,
            rootMessage = rootMessage,
            frames = frames.distinctBy { "${it.className}.${it.method}" },
            suspectedMods = (StackTraceTools.suspectedModIds(text) + sectionMods).distinct(),
            mixinMods = mixinMods.toList(),
            modFailures = failures,
            duplicates = duplicates,
            modFiles = modFiles,
            mcVersion = detail("Minecraft Version"),
            javaVersion = detail("Java Version"),
            maxHeapMb = heap,
            logFindings = LogScanner.scan(text),
            excerpt = excerpt,
            causes = causes.take(6),
            system = systemDetails(lines),
        )
    }

    private val systemKeys = listOf(
        "Operating System", "Java Version", "Java VM Version", "Memory", "CPUs", "Processor Name",
        "Graphics card #0 name", "Graphics card #0 vendor", "Graphics card #0 versionInfo", "Backend API", "Backend library",
        "Launched Version", "Type", "Is Modded", "Flywheel Backend", "Render Distance",
    )

    private fun systemDetails(lines: List<String>): Map<String, String> {
        val start = lines.indexOfFirst { it.trim() == "-- System Details --" }
        if (start < 0) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (l in lines.drop(start + 1)) {
            val t = l.trim()
            val key = t.substringBefore(':', "").trim()
            if (key in systemKeys && key !in out) out[key] = StackTraceTools.stripPaths(t.substringAfter(':').trim()).take(160)
        }
        return out
    }

    private fun parseSections(lines: List<String>): Pair<List<ModFailure>, List<String>> {
        val failures = mutableListOf<ModFailure>()
        val ids = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val h = sectionHeader.matchEntire(lines[i].trim())
            val id = h?.groupValues?.get(1)?.let {
                when {
                    it.startsWith("MOD ") -> it.removePrefix("MOD ").trim()
                    it.startsWith("Mod loading issue for:") -> it.substringAfter(":").trim()
                    else -> null
                }
            }
            if (id == null) { i++; continue }
            ids += id
            var file: String? = null
            var failure: String? = null
            var exception: String? = null
            var j = i + 1
            while (j < lines.size && !lines[j].trim().matches(sectionHeader) && !lines[j].startsWith("Stacktrace:")) {
                val t = lines[j].trim()
                when {
                    t.startsWith("Mod File:", true) -> file = t.substringAfter(":").trim()
                    t.startsWith("Failure message:") -> {
                        val sb = StringBuilder(t.substringAfter(":").trim())
                        var k = j + 1
                        while (k < lines.size && lines[k].startsWith("\t\t") && lines[k].isNotBlank()) { sb.append('\n').append(lines[k].trim()); k++ }
                        failure = sb.toString().trim()
                        j = k - 1
                    }
                    t.startsWith("Exception message:") -> exception = t.substringAfter(":").trim().takeUnless { it.startsWith("<No associated") }
                }
                j++
            }
            if (failure != null) failures += ModFailure(id, file, failure, exception)
            i = j
        }
        return failures to ids
    }

    private fun parseLegacyDuplicates(lines: List<String>): Map<String, List<String>> {
        val i = lines.indexOfFirst { it.trim() == "Duplicate Mods:" }
        if (i < 0) return emptyMap()
        val map = LinkedHashMap<String, MutableList<String>>()
        var j = i + 1
        while (j < lines.size && lines[j].startsWith("\t")) {
            val t = lines[j].trim()
            val id = t.substringBefore(" : ").trim()
            val path = t.substringAfter(" : ", "").trim()
            if (id.isNotEmpty() && path.isNotEmpty()) map.getOrPut(id) { mutableListOf() } += File(path.replace('\\', '/')).name
            j++
        }
        return map
    }

    private fun parseModList(lines: List<String>): Map<String, String> {
        val i = lines.indexOfFirst { it.trim().startsWith("Mod List:") }
        if (i < 0) return emptyMap()
        val map = LinkedHashMap<String, String>()
        var j = i + 1
        while (j < lines.size && lines[j].contains('|')) {
            val cols = lines[j].split('|').map { it.trim() }
            if (cols.size >= 3 && cols[2].isNotEmpty()) map[cols[2]] = cols[0]
            j++
        }
        return map
    }
}

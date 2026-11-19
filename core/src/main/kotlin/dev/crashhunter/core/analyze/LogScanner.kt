package dev.crashhunter.core.analyze

enum class FindingKind {
    MISSING_DEPENDENCY, DEPENDENCY_VERSION, LOADER_VERSION, MC_VERSION, DUPLICATE_MOD, JAVA_VERSION, OUT_OF_MEMORY,
    MIXIN_FAILURE, CORRUPT_JAR, CLIENT_ONLY_ON_SERVER, CONFIG_ERROR, GPU_DRIVER, WATCHDOG_HANG, WRONG_LOADER,
    INCOMPATIBLE_MOD, MIXIN_NO_REFMAP,
}

data class LogFinding(
    val kind: FindingKind,
    val mod: String? = null,
    val other: String? = null,
    val required: String? = null,
    val actual: String? = null,
    val line: String = "",
    val config: String? = null,
)

object LogScanner {
    private data class Rule(val regex: Regex, val make: (MatchResult, String) -> LogFinding?)

    private fun r(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

    private val javaClassVersions = mapOf(52 to "8", 55 to "11", 60 to "16", 61 to "17", 65 to "21", 66 to "22", 69 to "25")

    private val rules = listOf(
        Rule(r("""Mod ([\w\-]+) requires ([\w\-]+) ([\w.\-+]+) or above""")) { m, _ ->
            val dep = m.groupValues[2].lowercase()
            val kind = when (dep) {
                "forge", "neoforge", "fml", "javafml" -> FindingKind.LOADER_VERSION
                "minecraft" -> FindingKind.MC_VERSION
                else -> FindingKind.MISSING_DEPENDENCY
            }
            LogFinding(kind, m.groupValues[1], m.groupValues[2], "[" + m.groupValues[3] + ",)", line = m.value)
        },
        Rule(r("""Mod ID: '([\w\-]+)', Requested by: '([\w\-]+)', Expected range: '([^']*)', Actual version: '([^']*)'""")) { m, _ ->
            val dep = m.groupValues[1].lowercase()
            val missing = m.groupValues[4].contains("MISSING", true)
            val kind = when {
                dep == "minecraft" -> FindingKind.MC_VERSION
                dep == "forge" || dep == "neoforge" -> FindingKind.LOADER_VERSION
                missing -> FindingKind.MISSING_DEPENDENCY
                else -> FindingKind.DEPENDENCY_VERSION
            }
            LogFinding(kind, m.groupValues[2], m.groupValues[1], m.groupValues[3], m.groupValues[4].takeUnless { missing }, m.value)
        },
        Rule(r("""Mod '[^']*' \(([\w\-]+)\) \S+ requires (?:version )?(.+?) of (?:mod )?'[^']*' \(([\w\-]+)\), (?:but only the wrong version is present: ([\w.\-+]+)|which is missing)""")) { m, _ ->
            val dep = m.groupValues[3].lowercase()
            val missing = m.groupValues[4].isEmpty()
            val kind = when {
                dep == "minecraft" -> FindingKind.MC_VERSION
                dep == "fabricloader" || dep == "quilt_loader" -> FindingKind.LOADER_VERSION
                missing -> FindingKind.MISSING_DEPENDENCY
                else -> FindingKind.DEPENDENCY_VERSION
            }
            LogFinding(kind, m.groupValues[1], m.groupValues[3], m.groupValues[2], m.groupValues[4].ifEmpty { null }, m.value)
        },
        Rule(r("""Mod '[^']*' \(([\w\-]+)\) \S+ is incompatible with (?:version .+? of )?(?:mod )?'[^']*' \(([\w\-]+)\)""")) { m, _ ->
            LogFinding(FindingKind.INCOMPATIBLE_MOD, m.groupValues[1], m.groupValues[2], line = m.value)
        },
        Rule(r("""Found duplicate mods?:?\s*Mod ID: '([\w\-]+)' from mod files: (.+)""")) { m, _ ->
            LogFinding(FindingKind.DUPLICATE_MOD, m.groupValues[1], m.groupValues[2].trim(), line = m.value)
        },
        Rule(r("""Mod ID ['"]?([\w\-]+)['"]? is provided by multiple mods|Duplicate mods? found:? *([\w\-]+)?|DuplicateModsFoundException""")) { m, _ ->
            LogFinding(FindingKind.DUPLICATE_MOD, (m.groupValues[1].ifEmpty { m.groupValues[2] }).ifEmpty { null }, line = m.value)
        },
        Rule(r("""compiled by a more recent version of the Java Runtime \(class file version (\d+)(?:\.\d+)?\), this version of the Java Runtime only recognizes class file versions up to (\d+)""")) { m, _ ->
            LogFinding(FindingKind.JAVA_VERSION, required = javaClassVersions[m.groupValues[1].toInt()] ?: m.groupValues[1], actual = javaClassVersions[m.groupValues[2].toInt()] ?: m.groupValues[2], line = m.value)
        },
        Rule(r("""UnsupportedClassVersionError: ([\w/$.]+) .*class file version (\d+)""")) { m, _ ->
            LogFinding(FindingKind.JAVA_VERSION, other = m.groupValues[1].replace('/', '.'), required = javaClassVersions[m.groupValues[2].toInt()], line = m.value)
        },
        Rule(r("""requires Java (\d+)|Minecraft \S+ requires (?:running )?(?:the game with )?Java (\d+)""")) { m, _ ->
            LogFinding(FindingKind.JAVA_VERSION, required = m.groupValues[1].ifEmpty { m.groupValues[2] }, line = m.value)
        },
        Rule(r("""OutOfMemoryError(?::\s*(Java heap space|GC overhead limit exceeded|Metaspace|unable to create native thread|Direct buffer memory))?""")) { m, _ ->
            LogFinding(FindingKind.OUT_OF_MEMORY, other = m.groupValues[1].ifEmpty { null }, line = m.value)
        },
        Rule(r("""Mixin apply(?: for mod ([\w\-]+))? failed ([\w.\-]+\.json):([\w.$]+)(?: from mod ([\w\-]+))? -> ([\w.$]+)(?::\s*(.+))?""")) { m, _ ->
            LogFinding(FindingKind.MIXIN_FAILURE, m.groupValues[4].ifEmpty { m.groupValues[1] }.ifEmpty { null }, m.groupValues[5], line = m.value.take(300), config = m.groupValues[2])
        },
        Rule(r("""Mixin (?:prepare|transformation) (?:for mod ([\w\-]+) )?failed""")) { m, _ ->
            LogFinding(FindingKind.MIXIN_FAILURE, m.groupValues[1].ifEmpty { null }, line = m.value)
        },
        Rule(r("""Critical injection failure: .+? in ([\w.\-]+\.json):([\w.$]+)(?: from mod ([\w\-]+))?""")) { m, _ ->
            LogFinding(FindingKind.MIXIN_FAILURE, m.groupValues[3].ifEmpty { null }, m.groupValues[2], line = m.value.take(300), config = m.groupValues[1])
        },
        Rule(r("""(?:Critical injection failure|InvalidInjectionException).*? in ([\w.$]+)\.(?: No refMap loaded\.)?.*?-> ([\w.\-]+\.json):([\w.$]+)""")) { m, _ ->
            LogFinding(FindingKind.MIXIN_FAILURE, null, m.groupValues[1], line = m.value.take(300), config = m.groupValues[2])
        },
        Rule(r("""MixinApplyError: Mixin \[([\w.\-]+\.json):([\w.$]+)\]""")) { m, _ ->
            LogFinding(FindingKind.MIXIN_FAILURE, null, null, line = m.value.take(300), config = m.groupValues[1])
        },
        Rule(r("""Reference map '([\w.\-/]+)' for ([\w.\-]+\.json) could not be read""")) { m, _ ->
            LogFinding(FindingKind.MIXIN_NO_REFMAP, null, m.groupValues[1], line = m.value.take(300), config = m.groupValues[2])
        },
        Rule(r("""(zip END header not found|invalid CEN header|ZipException|Invalid or corrupt jarfile|Failed to read (?:mod )?file|error in opening zip file)(?:.*?([\w.\-+]+\.jar))?""")) { m, _ ->
            LogFinding(FindingKind.CORRUPT_JAR, other = m.groupValues[2].ifEmpty { null }, line = m.value.take(300))
        },
        Rule(r("""Attempted to load class (net/minecraft/client/[\w/$]+) for invalid dist DEDICATED_SERVER""")) { m, _ ->
            LogFinding(FindingKind.CLIENT_ONLY_ON_SERVER, other = m.groupValues[1].replace('/', '.'), line = m.value)
        },
        Rule(r("""ClassMetadataNotFoundException: (net\.minecraft\.client\.[\w.$]+)""")) { m, _ ->
            LogFinding(FindingKind.CLIENT_ONLY_ON_SERVER, other = m.groupValues[1], line = m.value)
        },
        Rule(r("""Cannot load class net\.minecraft\.client\.\S+ in environment type SERVER""")) { m, _ ->
            LogFinding(FindingKind.CLIENT_ONLY_ON_SERVER, line = m.value)
        },
        Rule(r("""(?:Failed loading config file|Configuration file|Failed to load config) ([^\s,]+\.(?:toml|json5?|cfg|properties))""")) { m, _ ->
            LogFinding(FindingKind.CONFIG_ERROR, other = m.groupValues[1].replace('\\', '/').substringAfterLast('/'), line = m.value)
        },
        Rule(r("""nightconfig\.core\.io\.ParsingException|ParsingException: Invalid|Not enough data available""")) { m, _ ->
            LogFinding(FindingKind.CONFIG_ERROR, line = m.value)
        },
        Rule(r("""Failed to initialize GLFW|WGL: The driver does not appear to support OpenGL|GLFW error 65542|Couldn't set pixel format|No OpenGL context|GL_VERSION.*not supported""")) { m, _ ->
            LogFinding(FindingKind.GPU_DRIVER, line = m.value)
        },
        Rule(r("""(?:ServerWatchdog|A single server tick) .*took ([\d.]+) seconds""")) { m, _ ->
            LogFinding(FindingKind.WATCHDOG_HANG, actual = m.groupValues[1], line = m.value)
        },
        Rule(r("""(?:File|Mod file) ([^\s]+\.jar) (?:is a|is for) (Fabric|Forge|NeoForge|Quilt)\b[^\n]*(?:cannot be loaded|not supported)""")) { m, _ ->
            LogFinding(FindingKind.WRONG_LOADER, other = m.groupValues[1].replace('\\', '/').substringAfterLast('/'), required = m.groupValues[2], line = m.value)
        },
    )

    fun scan(text: String, maxBytes: Int = 4 * 1024 * 1024): List<LogFinding> {
        val body = if (text.length > maxBytes) text.substring(0, maxBytes / 2) + "\n" + text.substring(text.length - maxBytes / 2) else text
        val out = LinkedHashMap<String, LogFinding>()
        for (line in body.lineSequence()) {
            if (line.length > 4000) continue
            for (rule in rules) {
                val m = rule.regex.find(line) ?: continue
                val f = rule.make(m, line) ?: continue
                out.putIfAbsent("${f.kind}|${f.mod}|${f.other}|${f.config}", f)
            }
        }
        return out.values.toList()
    }
}

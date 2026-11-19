package dev.crashhunter.core.analyze

import java.io.File

enum class SourceKind { CRASH_REPORT, HS_ERR, LATEST_LOG, MODS_CHECK }

data class StackFrame(val className: String, val method: String, val jarHint: String? = null, val modHint: String? = null, val src: String? = null) {
    override fun toString(): String = "$className.$method"
}

data class ModFailure(val modId: String, val file: String?, val message: String, val exception: String?)

data class CrashSource(val kind: SourceKind, val file: File?, val timeMs: Long)

data class ParsedCrash(
    val source: CrashSource,
    val description: String? = null,
    val exceptionType: String? = null,
    val message: String? = null,
    val rootCause: String? = null,
    val rootMessage: String? = null,
    val frames: List<StackFrame> = emptyList(),
    val suspectedMods: List<String> = emptyList(),
    val mixinMods: List<String> = emptyList(),
    val modFailures: List<ModFailure> = emptyList(),
    val duplicates: Map<String, List<String>> = emptyMap(),
    val modFiles: Map<String, String> = emptyMap(),
    val mcVersion: String? = null,
    val javaVersion: String? = null,
    val maxHeapMb: Long? = null,
    val nativeFrame: String? = null,
    val logFindings: List<LogFinding> = emptyList(),
    val excerpt: String = "",
    val causes: List<Pair<String, String?>> = emptyList(),
    val system: Map<String, String> = emptyMap(),
) {
    val effectiveException: String? get() = rootCause ?: exceptionType
    val effectiveMessage: String? get() = if (rootCause != null) rootMessage else message
    val allText: String get() = listOfNotNull(description, exceptionType, message, rootCause, rootMessage).joinToString("\n")
}

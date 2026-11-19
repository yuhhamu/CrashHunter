package dev.crashhunter.core.analyze

enum class Cause {
    MISSING_DEPENDENCY, DEPENDENCY_VERSION, LOADER_VERSION, MC_VERSION, WRONG_LOADER, DUPLICATE_MOD, JAVA_VERSION,
    OUT_OF_MEMORY, MIXIN_FAILURE, CORRUPT_JAR, CLASS_MISMATCH, CLIENT_ONLY_ON_SERVER, CONFIG_ERROR, GPU_DRIVER,
    MIXIN_NO_REFMAP, KNOWN_BROKEN, KNOWN_CONFLICT, KNOWN_FIX, NATIVE_CRASH, WATCHDOG_HANG, INCOMPATIBLE_MOD, MOD_LOAD_ERROR, MOD_RUNTIME_ERROR, NOT_A_MOD, CRASH_TOOL, NO_PROBLEM, UNKNOWN;

    val environmental: Boolean get() = this in setOf(JAVA_VERSION, OUT_OF_MEMORY, GPU_DRIVER, NATIVE_CRASH, NOT_A_MOD, CONFIG_ERROR)

    companion object {
        fun of(kind: FindingKind): Cause = when (kind) {
            FindingKind.MISSING_DEPENDENCY -> MISSING_DEPENDENCY
            FindingKind.DEPENDENCY_VERSION -> DEPENDENCY_VERSION
            FindingKind.LOADER_VERSION -> LOADER_VERSION
            FindingKind.MC_VERSION -> MC_VERSION
            FindingKind.DUPLICATE_MOD -> DUPLICATE_MOD
            FindingKind.JAVA_VERSION -> JAVA_VERSION
            FindingKind.OUT_OF_MEMORY -> OUT_OF_MEMORY
            FindingKind.MIXIN_FAILURE -> MIXIN_FAILURE
            FindingKind.CORRUPT_JAR -> CORRUPT_JAR
            FindingKind.CLIENT_ONLY_ON_SERVER -> CLIENT_ONLY_ON_SERVER
            FindingKind.CONFIG_ERROR -> CONFIG_ERROR
            FindingKind.GPU_DRIVER -> GPU_DRIVER
            FindingKind.WATCHDOG_HANG -> WATCHDOG_HANG
            FindingKind.WRONG_LOADER -> WRONG_LOADER
            FindingKind.INCOMPATIBLE_MOD -> INCOMPATIBLE_MOD
            FindingKind.MIXIN_NO_REFMAP -> MIXIN_NO_REFMAP
        }
    }
}

data class Finding(
    val cause: Cause,
    val mod: String? = null,
    val other: String? = null,
    val required: String? = null,
    val actual: String? = null,
    val line: String = "",
    val jar: String? = null,
    val config: String? = null,
)

enum class EvidenceKind { LOADER_REPORT, MOD_FAILURE, SUSPECTED_BY_REPORT, TOP_FRAME, FRAME, MIXIN, DUPLICATE, STATIC_CHECK, BISECT }

data class Evidence(val kind: EvidenceKind, val detail: String)

data class Suspect(
    val jar: String?,
    val modId: String?,
    val name: String,
    val score: Int,
    val evidence: List<Evidence>,
)

data class Diagnosis(
    val source: CrashSource,
    val cause: Cause,
    val findings: List<Finding>,
    val suspects: List<Suspect>,
    val title: String,
    val detail: String,
    val solutions: List<String>,
    val fingerprint: CrashFingerprint?,
    val parsed: ParsedCrash?,
    val otherProblems: List<String> = emptyList(),
    val solutionsAfterBisect: List<String> = solutions,
) {
    val topSuspect: Suspect? get() = suspects.firstOrNull()
}

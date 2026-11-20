package dev.crashhunter.core.fix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class FixKind(val defaultOn: Boolean, val network: Boolean) {
    DUPLICATE(true, false),
    WRONG_LOADER(true, false),
    MC_MISMATCH(true, false),
    CORRUPT_JAR(true, false),
    ENABLE_PROVIDER(true, false),
    CULPRIT(false, false),
    MEMORY(true, false),
    JAVA(true, false),
    ADD_DEPENDENCY(false, true),
    UPDATE_DEPENDENCY(false, true),
    REPLACE_VERSION(false, true),
    REPLACE_BROKEN(true, true),
    DB_FIX(true, true),
    LOADER_UPDATE(false, true),
    CONFIG_RESET(false, false),
    CRASH_TOOL(true, false);

    companion object {
        fun enabledFrom(map: Map<String, Boolean>): Set<FixKind> = entries.filter { map[it.name] ?: it.defaultOn }.toSet()
    }
}

enum class RemoteSource { MODRINTH, CURSEFORGE, CURSEFORGE_PRISM }

@Serializable
data class RemoteFile(
    val source: RemoteSource,
    val fileName: String,
    val url: String? = null,
    val sha1: String? = null,
    val sha512: String? = null,
    val projectId: String,
    val fileId: String,
    val versionLabel: String,
    val pageUrl: String? = null,
    val projectName: String? = null,
    val author: String? = null,
)

@Serializable
sealed interface FixAction

@Serializable
@SerialName("disable")
data class DisableMod(val file: String) : FixAction

@Serializable
@SerialName("enable")
data class EnableMod(val file: String) : FixAction

@Serializable
@SerialName("cfg")
data class SetInstanceValue(val key: String, val value: String) : FixAction

@Serializable
@SerialName("add")
data class AddMod(val candidates: List<RemoteFile>, val expectModId: String? = null, val range: String? = null) : FixAction

@Serializable
@SerialName("replace")
data class ReplaceMod(
    val file: String,
    val candidates: List<RemoteFile>,
    val expectModId: String? = null,
    val range: String? = null,
    val ranges: List<String> = emptyList(),
    val needRefmap: Boolean = false,
) : FixAction

@Serializable
@SerialName("component")
data class SetComponentVersion(val uid: String, val version: String) : FixAction

@Serializable
@SerialName("config")
data class ResetConfig(val path: String) : FixAction

@Serializable
data class FixProposal(
    val id: String,
    val kind: FixKind,
    val target: String,
    val params: Map<String, String> = emptyMap(),
    val actions: List<FixAction>,
    val manualUrl: String? = null,
) {
    val fromDb: Boolean get() = params["db"] == "1"
}

@Serializable
sealed interface DoneStep

@Serializable
@SerialName("renamed")
data class Renamed(val from: String, val to: String) : DoneStep

@Serializable
@SerialName("added")
data class FileAdded(val path: String) : DoneStep

@Serializable
@SerialName("moved")
data class FileMoved(val from: String, val backup: String) : DoneStep

@Serializable
@SerialName("backedUp")
data class FileBackedUp(val path: String, val backup: String) : DoneStep

enum class FixState { APPLIED, VERIFYING, VERIFIED, KEPT_NEW_FAILURE, UNDONE, FAILED }

@Serializable
data class FixRecord(
    val id: String,
    val instanceId: String,
    val instanceDir: String,
    val time: Long,
    val proposals: List<FixProposal>,
    val steps: List<DoneStep>,
    val state: FixState = FixState.APPLIED,
    val note: String = "",
    val chainId: String? = null,
    val round: Int = 1,
)

package dev.crashhunter.core.launcher

import dev.crashhunter.core.model.GameInstance
import java.io.File

data class LauncherInstallation(val exe: File?, val dataDir: File, val instancesDir: File, val kind: String = "prism")

interface InstanceFormat {
    val kind: String
    fun isInstance(dir: File): Boolean
    fun read(dir: File): GameInstance
    fun settingsFile(instance: GameInstance): File
    fun value(instance: GameInstance, key: String): String?
    fun setValue(instance: GameInstance, key: String, value: String)
    fun componentsFile(instance: GameInstance): File
    fun setComponentVersion(instance: GameInstance, uid: String, version: String)
    fun memoryMb(instance: GameInstance, dataDir: File?): Int
    fun ownsCopyFile(name: String): Boolean
    fun writeCopyFiles(source: GameInstance, target: File, label: String)
    fun signature(dir: File): String
}

interface Launcher {
    val kind: String
    val displayName: String
    val installation: LauncherInstallation
    val format: InstanceFormat
    fun instances(includeWorkCopies: Boolean = false): List<GameInstance>
    fun controller(logDir: File? = null): GameLauncher
}

object Formats {
    private val formats = LinkedHashMap<String, InstanceFormat>()

    fun register(format: InstanceFormat) { formats[format.kind] = format }

    fun of(kind: String): InstanceFormat = formats[kind] ?: error("unknown launcher format: $kind")

    fun of(instance: GameInstance): InstanceFormat = of(instance.launcher)

    init { register(dev.crashhunter.core.prism.PrismFormat) }
}

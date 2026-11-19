package dev.crashhunter.core.prism

import dev.crashhunter.core.fix.FixApplier
import dev.crashhunter.core.launcher.GameLauncher
import dev.crashhunter.core.launcher.InstanceFormat
import dev.crashhunter.core.launcher.Launcher
import dev.crashhunter.core.launcher.LauncherInstallation
import dev.crashhunter.core.model.GameInstance
import java.io.File

object PrismFormat : InstanceFormat {
    const val KIND = "prism"
    private const val CFG = "instance.cfg"
    private const val PACK = "mmc-pack.json"
    private val volatileKeys = setOf("lastLaunchTime", "totalTimePlayed", "lastTimePlayed", "LastLaunchTime", "linkedInstances")
    private val QUIET_CONSOLE = listOf("OverrideConsole" to "true", "ShowConsole" to "false", "AutoCloseConsole" to "false", "ShowConsoleOnError" to "false")

    override val kind = KIND

    override fun isInstance(dir: File) = File(dir, CFG).isFile

    override fun read(dir: File): GameInstance = InstanceScanner.read(dir)

    override fun settingsFile(instance: GameInstance) = File(instance.dir, CFG)

    override fun value(instance: GameInstance, key: String): String? = IniFile.read(settingsFile(instance))[key]

    override fun setValue(instance: GameInstance, key: String, value: String) {
        val f = settingsFile(instance)
        f.writeText(IniFile.withValue(if (f.isFile) f.readText() else "", key, value))
    }

    override fun componentsFile(instance: GameInstance) = File(instance.dir, PACK)

    override fun setComponentVersion(instance: GameInstance, uid: String, version: String) {
        val f = componentsFile(instance)
        f.writeText(FixApplier.withComponentVersion(f.readText(), uid, version))
    }

    override fun memoryMb(instance: GameInstance, dataDir: File?): Int {
        if (value(instance, "OverrideMemory").equals("true", true)) value(instance, "MaxMemAlloc")?.toIntOrNull()?.let { return it }
        return globalMaxMem(dataDir) ?: 4096
    }

    fun globalMaxMem(dataDir: File?): Int? = dataDir?.let { IniFile.read(File(it, "prismlauncher.cfg"))["MaxMemAlloc"]?.toIntOrNull() }

    override fun ownsCopyFile(name: String) = name == CFG

    override fun writeCopyFiles(source: GameInstance, target: File, label: String) {
        val src = settingsFile(source)
        val text = if (src.isFile) src.readText() else ""
        var out = IniFile.withValue(text, "name", "${source.name} ($label)")
        for ((k, v) in QUIET_CONSOLE) out = IniFile.withValue(out, k, v)
        File(target, CFG).writeText(out)
    }

    override fun signature(dir: File): String {
        val cfg = runCatching { File(dir, CFG).readLines().filter { line -> volatileKeys.none { line.startsWith("$it=") } }.hashCode() }.getOrDefault(0)
        return "$cfg:${File(dir, PACK).lastModified()}"
    }
}

class PrismLauncher(override val installation: LauncherInstallation) : Launcher {
    override val kind = PrismFormat.KIND
    override val displayName = "Prism Launcher"
    override val format: InstanceFormat = PrismFormat

    override fun instances(includeWorkCopies: Boolean) = InstanceScanner.scan(installation.instancesDir, includeWorkCopies)

    override fun controller(logDir: File?): GameLauncher = PrismController(installation, logDir = logDir)

    companion object {
        fun detect(overrideExe: File? = null, overrideDataDir: File? = null): PrismLauncher? =
            PrismLocator.detect(overrideExe, overrideDataDir)?.let(::PrismLauncher)
    }
}

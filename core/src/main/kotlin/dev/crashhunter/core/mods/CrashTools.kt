package dev.crashhunter.core.mods

import java.io.File

object CrashTools {
    val IDS = setOf("crash_assistant")

    fun find(index: ModIndex): List<ModJar> = index.jars.filter { j -> j.enabled && j.mods.any { it.modId.lowercase() in IDS } }

    fun disable(modsDir: File): List<String> =
        find(ModIndex.load(modsDir)).mapNotNull { j -> j.displayName.takeIf { j.file.renameTo(File(j.file.parentFile, j.fileName + ".disabled")) } }
}

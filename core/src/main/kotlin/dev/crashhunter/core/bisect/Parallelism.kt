package dev.crashhunter.core.bisect

import dev.crashhunter.core.model.GameInstance
import java.io.File

object Parallelism {
    fun instanceMemoryMb(instance: GameInstance, dataDir: File?): Int = dev.crashhunter.core.launcher.Formats.of(instance).memoryMb(instance, dataDir)

    fun freeMemoryMb(): Long = runCatching {
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).freeMemorySize / (1024 * 1024)
    }.getOrDefault(0L)

    fun choose(requested: Int, max: Int, perGameMb: Int, freeMb: Long): Int {
        val cap = maxOf(1, max)
        if (requested > 0) return requested.coerceIn(1, cap)
        val need = perGameMb + 1024
        return (freeMb / need).toInt().coerceIn(1, cap)
    }
}

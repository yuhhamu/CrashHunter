package dev.crashhunter.core.bisect

import dev.crashhunter.core.model.GameInstance
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

object LoadGuard {
    private val MAX_THREADS = Regex("""(?m)^([ \t]*maxThreads[ \t]*=[ \t]*)(-?\d+)[ \t]*$""")

    fun singleThreaded(instance: GameInstance): Boolean {
        val f = File(instance.gameDir, "config/fml.toml")
        if (!f.isFile) return false
        return MAX_THREADS.find(f.readText())?.groupValues?.get(2) == "1"
    }

    fun limitThreads(instance: GameInstance): Boolean {
        if (!WorkCopy.isWorkCopy(instance.dir)) return false
        return writeLimit(File(instance.gameDir, "config/fml.toml"))
    }

    fun writeLimit(f: File): Boolean {
        val old = if (f.isFile) f.readText() else ""
        val next = when {
            MAX_THREADS.containsMatchIn(old) -> MAX_THREADS.replace(old) { it.groupValues[1] + "1" }
            old.isEmpty() -> "maxThreads = 1\n"
            else -> old.trimEnd() + "\nmaxThreads = 1\n"
        }
        if (next == old) return false
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".crashhunter.tmp")
        tmp.writeText(next)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    fun cpuMs(pids: List<Long>): Long = pids.sumOf { pid ->
        ProcessHandle.of(pid).flatMap { it.info().totalCpuDuration() }.map { it.toMillis() }.orElse(0L)
    }

    fun threadDump(pids: List<Long>, out: File): Boolean {
        for (pid in pids) {
            val cmd = ProcessHandle.of(pid).flatMap { it.info().command() }.orElse(null) ?: continue
            val bin = File(cmd).parentFile ?: continue
            val tool = listOf("jcmd.exe", "jcmd").map { File(bin, it) }.firstOrNull { it.isFile }
                ?: listOf("jstack.exe", "jstack").map { File(bin, it) }.firstOrNull { it.isFile } ?: continue
            val args = if (tool.name.startsWith("jcmd")) listOf(tool.path, pid.toString(), "Thread.print", "-l") else listOf(tool.path, "-l", pid.toString())
            val ok = runCatching {
                out.parentFile.mkdirs()
                val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(out).start()
                if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); false } else out.length() > 0
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}

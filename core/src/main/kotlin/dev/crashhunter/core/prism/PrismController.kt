package dev.crashhunter.core.prism

import dev.crashhunter.core.launcher.GameLauncher
import dev.crashhunter.core.launcher.LauncherInstallation
import dev.crashhunter.core.launcher.ProcessFinder
import dev.crashhunter.core.launcher.WindowArranger
import java.io.File
import java.util.concurrent.TimeUnit

class PrismController(
    private val install: LauncherInstallation,
    private val finder: ProcessFinder = ProcessFinder.default(),
    private val logDir: File? = null,
) : GameLauncher {
    private val ownPid = ProcessHandle.current().pid()
    private val launcherArg = Regex("""prismlauncher"?\s+-l\s""")

    private fun marker(instanceId: String) = "/${install.instancesDir.name}/$instanceId/".lowercase()

    override fun gamePids(instanceId: String): List<Long> {
        val m = marker(instanceId)
        return finder.find { cmd ->
            val c = cmd.replace('\\', '/').lowercase()
            c.contains(m) && !c.contains("prismlauncher.exe") && !launcherArg.containsMatchIn(c)
        }.filter { it != ownPid }
    }

    fun launcherPids(instanceId: String): List<Long> = finder.find { cmd ->
        val c = cmd.trim()
        c.contains("prismlauncher", true) && (c.contains("-l \"$instanceId\"") || c.contains("-l $instanceId ") || c.endsWith("-l $instanceId"))
    }.filter { it != ownPid }

    private fun launchLog(instanceId: String): File? = logDir?.let { File(it, "prism-launch-" + Integer.toHexString(instanceId.hashCode()) + ".log") }

    override fun launch(instanceId: String): Process {
        val exe = install.exe ?: error("prismlauncher not found")
        val pb = ProcessBuilder(exe.absolutePath, "-l", instanceId).directory(exe.parentFile)
        val log = launchLog(instanceId)
        if (log != null) {
            log.parentFile.mkdirs()
            pb.redirectOutput(ProcessBuilder.Redirect.to(log)).redirectErrorStream(true)
        } else pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true)
        return pb.start()
    }

    override fun handoffFailed(instanceId: String, spawned: Process?): Boolean {
        if (spawned == null || spawned.isAlive) return false
        val text = launchLog(instanceId)?.let { runCatching { it.readText() }.getOrNull() } ?: return false
        return HANDOFF_FAILED.containsMatchIn(text)
    }

    override fun clearStaleLaunchers(instanceId: String): Int {
        val stale = finder.find { cmd ->
            val c = cmd.replace('\\', '/').trim().lowercase()
            (c.contains("prismlauncher.exe") || c.startsWith("prismlauncher")) && LAUNCH_ARG.containsMatchIn(c)
        }
            .filter { it != ownPid }
            .filter { pid -> ProcessHandle.of(pid).map { h -> h.isAlive && h.descendants().noneMatch { it.isAlive } }.orElse(false) }
        stale.forEach { kill(it, force = true) }
        return stale.size
    }

    override fun refreshInstances() {
        val probe = File(install.instancesDir, ".crashhunter-rescan")
        runCatching { probe.mkdirs(); Thread.sleep(500); probe.delete() }
    }

    companion object {
        val HANDOFF_FAILED = Regex("Unable to redirect command", RegexOption.IGNORE_CASE)
        private val LAUNCH_ARG = Regex("""\s-l\s""")
    }

    override fun stop(instanceId: String, spawned: Process?) = stop(instanceId, spawned, 4_000, 20_000)

    override fun arrangeWindows(pids: List<Long>, titlePrefix: String?, minimize: Boolean): Int = WindowArranger.arrange(pids, titlePrefix, minimize)

    override fun launcherProcesses(): List<Long> = finder.find { cmd -> cmd.replace('\\', '/').lowercase().contains("prismlauncher.exe") || cmd.trim().lowercase().startsWith("prismlauncher") }
        .filter { it != ownPid }

    fun stop(instanceId: String, spawned: Process?, graceMs: Long, totalMs: Long) {
        val deadline = System.currentTimeMillis() + totalMs
        var pids = gamePids(instanceId)
        pids.forEach { kill(it, force = false) }
        val graceEnd = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < graceEnd && pids.any { alive(it) }) Thread.sleep(250)
        pids.filter { alive(it) }.forEach { kill(it, force = true) }
        while (System.currentTimeMillis() < deadline) {
            pids = gamePids(instanceId)
            if (pids.isEmpty()) break
            pids.forEach { kill(it, force = true) }
            Thread.sleep(500)
        }
    }

    override fun closeLaunchers(instanceIds: Collection<String>) {
        if (instanceIds.any { gamePids(it).isNotEmpty() }) return
        instanceIds.flatMap { launcherPids(it) }.distinct().forEach { kill(it, force = true) }
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun kill(pid: Long, force: Boolean) {
        if (pid == ownPid) return
        ProcessHandle.of(pid).ifPresent { h -> if (force) h.destroyForcibly() else h.destroy() }
    }
}

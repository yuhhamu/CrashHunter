package dev.crashhunter.core.launcher

import java.io.File
import java.util.concurrent.TimeUnit

interface ProcessFinder {
    fun find(predicate: (String) -> Boolean): List<Long>

    companion object {
        fun default(): ProcessFinder =
            if (System.getProperty("os.name").lowercase().contains("win")) WindowsProcessFinder() else JvmProcessFinder
    }
}

object JvmProcessFinder : ProcessFinder {
    override fun find(predicate: (String) -> Boolean): List<Long> =
        ProcessHandle.allProcesses().filter { p -> p.info().commandLine().map(predicate).orElse(false) }.map { it.pid() }.toList()
}

class WindowsProcessFinder(private val names: Set<String> = DEFAULT_NAMES) : ProcessFinder {
    companion object { val DEFAULT_NAMES = setOf("javaw.exe", "java.exe", "prismlauncher.exe") }

    override fun find(predicate: (String) -> Boolean): List<Long> = runCatching {
        val script = File.createTempFile("crashhunter-ps-", ".ps1")
        try {
            script.writeText(
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8\n" +
                "Get-CimInstance Win32_Process -Filter \"" + names.joinToString(" OR ") { "Name='$it'" } + "\" | " +
                    "ForEach-Object { [string]\$_.ProcessId + '|' + [string]\$_.CommandLine }",
                Charsets.UTF_8,
            )
            val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            p.waitFor(20, TimeUnit.SECONDS)
            out.lineSequence().mapNotNull { line ->
                val i = line.indexOf('|')
                if (i < 0) return@mapNotNull null
                val pid = line.substring(0, i).trim().toLongOrNull() ?: return@mapNotNull null
                if (predicate(line.substring(i + 1))) pid else null
            }.toList()
        } finally {
            script.delete()
        }
    }.getOrDefault(emptyList())
}

interface GameLauncher {
    fun launch(instanceId: String): Process?
    fun gamePids(instanceId: String): List<Long>
    fun stop(instanceId: String, spawned: Process?)
    fun arrangeWindows(pids: List<Long>, titlePrefix: String?, minimize: Boolean): Int = 0
    fun launcherProcesses(): List<Long> = emptyList()
    fun closeLaunchers(instanceIds: Collection<String>) {}
    fun handoffFailed(instanceId: String, spawned: Process?): Boolean = false
    fun clearStaleLaunchers(instanceId: String): Int = 0
    fun refreshInstances() {}
}

object WindowArranger {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun script(pids: List<Long>, titlePrefix: String?, minimize: Boolean): String {
        val prefix = titlePrefix?.filter { it.code in 32..126 && it != '\'' }
        return buildString {
            append("Add-Type -Namespace CrashHunter -Name Win -MemberDefinition '")
            append("[DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] public static extern bool SetWindowText(System.IntPtr h, string t); ")
            append("[DllImport(\"user32.dll\")] public static extern bool ShowWindowAsync(System.IntPtr h, int c);'\n")
            append("\$n = 0\n")
            append("foreach (\$id in @(${pids.joinToString(",")})) {\n")
            append("  \$p = Get-Process -Id \$id -ErrorAction SilentlyContinue\n")
            append("  if (\$p -and \$p.MainWindowHandle -ne [System.IntPtr]::Zero) {\n")
            if (prefix != null) append("    if (-not \$p.MainWindowTitle.StartsWith('$prefix')) { [CrashHunter.Win]::SetWindowText(\$p.MainWindowHandle, '$prefix' + \$p.MainWindowTitle) | Out-Null }\n")
            if (minimize) append("    [CrashHunter.Win]::ShowWindowAsync(\$p.MainWindowHandle, 7) | Out-Null\n")
            append("    \$n++\n  }\n}\n\$n\n")
        }
    }

    fun arrange(pids: List<Long>, titlePrefix: String?, minimize: Boolean): Int {
        if (!isWindows || pids.isEmpty() || (titlePrefix == null && !minimize)) return 0
        return runCatching {
            val file = File.createTempFile("crashhunter-win-", ".ps1")
            try {
                file.writeText(script(pids, titlePrefix, minimize), Charsets.UTF_8)
                val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.absolutePath)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(20, TimeUnit.SECONDS)
                out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull() ?: 0
            } finally {
                file.delete()
            }
        }.getOrDefault(0)
    }
}

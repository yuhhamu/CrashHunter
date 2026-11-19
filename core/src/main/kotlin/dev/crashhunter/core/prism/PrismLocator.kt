package dev.crashhunter.core.prism

import dev.crashhunter.core.launcher.LauncherInstallation
import java.io.File

object PrismLocator {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun detect(overrideExe: File? = null, overrideDataDir: File? = null, env: Map<String, String> = System.getenv()): LauncherInstallation? {
        val exe = overrideExe?.takeIf { it.isFile } ?: findExe(env)
        val dataDir = overrideDataDir?.takeIf { it.isDirectory } ?: dataDirFor(exe, env) ?: return null
        return LauncherInstallation(exe, dataDir, instancesDirOf(dataDir))
    }

    fun exeCandidates(env: Map<String, String> = System.getenv()): List<File> {
        val name = if (isWindows) "prismlauncher.exe" else "prismlauncher"
        val list = mutableListOf<File>()
        env["LOCALAPPDATA"]?.let { list += File(it, "Programs/PrismLauncher/$name") }
        env["ProgramFiles"]?.let { list += File(it, "PrismLauncher/$name") }
        env["ProgramFiles(x86)"]?.let { list += File(it, "PrismLauncher/$name") }
        if (isWindows) {
            list += registryDirs().map { File(it, name) }
            for (root in File.listRoots()) {
                list += File(root, "Program Files/PrismLauncher/$name")
                list += File(root, "Program Files (x86)/PrismLauncher/$name")
                list += File(root, "PrismLauncher/$name")
                val users = File(root, "Users").listFiles { f -> f.isDirectory } ?: emptyArray()
                for (u in users) list += File(u, "AppData/Local/Programs/PrismLauncher/$name")
            }
        } else {
            list += File("/usr/bin/prismlauncher")
            list += File("/usr/local/bin/prismlauncher")
            list += File("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher")
        }
        return list.distinctBy { it.absolutePath.lowercase() }
    }

    fun findExe(env: Map<String, String> = System.getenv()): File? = exeCandidates(env).firstOrNull { it.isFile }

    fun registryDirs(): List<File> = runCatching {
        val keys = listOf(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        )
        keys.flatMap { key ->
            val p = ProcessBuilder("reg", "query", key, "/s", "/f", "PrismLauncher").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader(java.nio.charset.Charset.forName(System.getProperty("native.encoding") ?: "UTF-8")).readText()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            parseRegistryDirs(out)
        }.distinctBy { it.absolutePath.lowercase() }
    }.getOrDefault(emptyList())

    fun parseRegistryDirs(output: String): List<File> = output.lineSequence().mapNotNull { line ->
        val m = Regex("""^\s*(InstallLocation|DisplayIcon|UninstallString)\s+REG_\w+\s+(.+)$""").find(line) ?: return@mapNotNull null
        var v = m.groupValues[2].trim().removeSurrounding("\"")
        if (m.groupValues[1] != "InstallLocation") v = v.substringBefore(",").removeSurrounding("\"").let { exe -> exe.substring(0, maxOf(exe.lastIndexOf('\\'), exe.lastIndexOf('/')).takeIf { it > 0 } ?: return@mapNotNull null) }
        v.takeIf { it.isNotBlank() && it.contains("prism", ignoreCase = true) }?.let(::File)
    }.toList()

    fun dataDirFor(exe: File?, env: Map<String, String> = System.getenv()): File? {
        val exeDir = exe?.parentFile
        if (exeDir != null && (File(exeDir, "portable.txt").isFile || File(exeDir, "prismlauncher.cfg").isFile)) return exeDir
        val roaming = env["APPDATA"]?.let { File(it, "PrismLauncher") }
        if (roaming != null && roaming.isDirectory) return roaming
        val home = System.getProperty("user.home")
        listOf(File(home, ".local/share/PrismLauncher"), File(home, "Library/Application Support/PrismLauncher"))
            .firstOrNull { it.isDirectory }?.let { return it }
        return exeDir?.takeIf { File(it, "instances").isDirectory }
    }

    fun instancesDirOf(dataDir: File): File {
        val cfg = File(dataDir, "prismlauncher.cfg")
        val value = if (cfg.isFile) IniFile.read(cfg)["InstanceDir"] else null
        if (value.isNullOrBlank()) return File(dataDir, "instances")
        val f = File(value)
        return if (f.isAbsolute) f else File(dataDir, value)
    }
}

object IniFile {
    fun read(file: File): Map<String, String> = parse(runCatching { file.readText(Charsets.UTF_8) }.getOrDefault(""))

    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) continue
            val i = line.indexOf('=')
            if (i <= 0) continue
            var v = line.substring(i + 1).trim()
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
            map[line.substring(0, i).trim()] = v
        }
        return map
    }

    fun withValue(text: String, key: String, value: String): String {
        val lines = text.lines().toMutableList()
        val header = lines.indexOfFirst { it.trim().startsWith("[") }
        val start = if (header < 0) 0 else lines.indexOfFirst { it.trim().equals("[General]", true) }.takeIf { it >= 0 }?.plus(1) ?: 0
        val end = if (header < 0) lines.size else (start until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        val idx = (start until end).firstOrNull { lines[it].trim().startsWith("$key=") }
        if (idx != null) lines[idx] = "$key=$value"
        else {
            val at = ((start until end).lastOrNull { lines[it].isNotBlank() } ?: (start - 1)) + 1
            lines.add(at, "$key=$value")
        }
        return lines.joinToString("\n")
    }
}

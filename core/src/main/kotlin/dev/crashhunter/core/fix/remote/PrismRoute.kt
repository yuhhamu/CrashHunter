package dev.crashhunter.core.fix.remote

import dev.crashhunter.core.model.GameInstance
import kotlinx.coroutines.delay
import org.tomlj.Toml
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class IndexEntry(val fileName: String, val modrinthId: String?, val modrinthVersion: String?, val curseProject: Long?, val curseFile: Long?, val sha1: String? = null, val sha512: String? = null)

object PrismModIndex {
    fun read(modsDir: File): List<IndexEntry> =
        (File(modsDir, ".index").listFiles { f -> f.isFile && f.name.endsWith(".pw.toml") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val t = Toml.parse(f.readText())
                val name = t.getString("filename") ?: return@runCatching null
                val format = t.getString("download.hash-format")?.lowercase()
                val hash = t.getString("download.hash")
                IndexEntry(name, t.getString("update.modrinth.mod-id"), t.getString("update.modrinth.version"),
                    t.getLong("update.curseforge.project-id"), t.getLong("update.curseforge.file-id"),
                    hash.takeIf { format == "sha1" }, hash.takeIf { format == "sha512" })
            }.getOrNull()
        }

    fun forFile(modsDir: File, fileName: String): IndexEntry? = read(modsDir).firstOrNull { it.fileName.equals(fileName.removeSuffix(".disabled"), true) }
}

class PrismImportDownloader(
    private val prismExe: () -> File?,
    private val instancesDir: () -> File?,
    private val waitMs: Long = 5 * 60_000,
    private val status: (String) -> Unit = {},
) {
    suspend fun download(instance: GameInstance, projectId: Long, fileId: Long, dest: File): File {
        val exe = prismExe() ?: error("prismlauncher.exe not set")
        val instDir = instancesDir() ?: error("instances folder unknown")
        val name = "CrashHunter-dl-" + System.currentTimeMillis()
        val zip = File(dest.parentFile, "$name.zip")
        writePack(zip, instance, projectId, fileId)
        status("prism-import")
        ProcessBuilder(exe.absolutePath, "--import", zip.absolutePath).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val deadline = System.currentTimeMillis() + waitMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val dir = (instDir.listFiles { f -> f.isDirectory && f.name.startsWith(name) } ?: emptyArray()).firstOrNull()
                val jar = dir?.let { d -> listOf("minecraft/mods", ".minecraft/mods").map { File(d, it) }.firstNotNullOfOrNull { m -> m.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.firstOrNull() } }
                if (jar != null && stable(jar)) {
                    dest.parentFile?.mkdirs()
                    jar.copyTo(dest, overwrite = true)
                    dir.deleteRecursively()
                    return dest
                }
                delay(1_000)
            }
            error("Prism import timed out")
        } finally {
            zip.delete()
        }
    }

    private suspend fun stable(f: File): Boolean {
        val a = f.length()
        delay(800)
        return a > 0 && a == f.length()
    }

    private fun writePack(zip: File, instance: GameInstance, projectId: Long, fileId: Long) {
        val p = instance.profile
        val loaderId = when (p.loader) {
            dev.crashhunter.core.model.Loader.FORGE, dev.crashhunter.core.model.Loader.FORGE_LEGACY -> "forge-${p.loaderVersion}"
            dev.crashhunter.core.model.Loader.NEOFORGE -> "neoforge-${p.loaderVersion}"
            dev.crashhunter.core.model.Loader.FABRIC -> "fabric-${p.loaderVersion}"
            dev.crashhunter.core.model.Loader.QUILT -> "quilt-${p.loaderVersion}"
            else -> null
        }
        val loaders = loaderId?.let { "[{\"id\":\"$it\",\"primary\":true}]" } ?: "[]"
        val manifest = "{\"minecraft\":{\"version\":\"${p.mcVersion}\",\"modLoaders\":$loaders},\"manifestType\":\"minecraftModpack\",\"manifestVersion\":1," +
            "\"name\":\"${zip.nameWithoutExtension}\",\"version\":\"1\",\"author\":\"CrashHunter\",\"files\":[{\"projectID\":$projectId,\"fileID\":$fileId,\"required\":true}],\"overrides\":\"overrides\"}"
        zip.parentFile?.mkdirs()
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("overrides/")); z.closeEntry()
        }
    }
}

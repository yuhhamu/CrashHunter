package dev.crashhunter.core.prism

import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.model.Loader
import dev.crashhunter.core.model.GameInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object InstanceScanner {
    const val WORK_COPY_SUFFIX = "-CrashHunter"

    private val json = Json { ignoreUnknownKeys = true }

    fun scan(instancesDir: File, includeWorkCopies: Boolean = false): List<GameInstance> =
        (instancesDir.listFiles { f -> f.isDirectory && File(f, "instance.cfg").isFile } ?: emptyArray())
            .filter { includeWorkCopies || !it.name.endsWith(WORK_COPY_SUFFIX) }
            .filter { !it.name.endsWith(dev.crashhunter.core.bisect.WorkCopy.STAGING_SUFFIX) && !it.name.startsWith(".") }
            .mapNotNull { runCatching { read(it) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun read(dir: File): GameInstance {
        val cfg = IniFile.read(File(dir, "instance.cfg"))
        val gameDir = listOf(File(dir, "minecraft"), File(dir, ".minecraft")).firstOrNull { it.isDirectory } ?: File(dir, "minecraft")
        return GameInstance(
            id = dir.name,
            name = cfg["name"]?.takeIf { it.isNotBlank() } ?: dir.name,
            dir = dir,
            gameDir = gameDir,
            profile = readProfile(File(dir, "mmc-pack.json")),
        )
    }

    fun readProfile(pack: File): GameProfile {
        if (!pack.isFile) return GameProfile(null, Loader.UNKNOWN, null)
        val root = runCatching { json.parseToJsonElement(pack.readText()).jsonObject }.getOrNull() ?: return GameProfile(null, Loader.UNKNOWN, null)
        val comps = runCatching { root["components"]!!.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
        fun ver(c: JsonObject) = c["version"]?.jsonPrimitive?.contentOrNull ?: c["cachedVersion"]?.jsonPrimitive?.contentOrNull
        var mc: String? = null
        var loader = Loader.VANILLA
        var loaderVersion: String? = null
        for (c in comps) {
            when (c["uid"]?.jsonPrimitive?.contentOrNull) {
                "net.minecraft" -> mc = ver(c)
                "net.minecraftforge" -> { loader = Loader.FORGE; loaderVersion = ver(c) }
                "net.neoforged" -> { loader = Loader.NEOFORGE; loaderVersion = ver(c) }
                "net.fabricmc.fabric-loader" -> { loader = Loader.FABRIC; loaderVersion = ver(c) }
                "org.quiltmc.quilt-loader" -> { loader = Loader.QUILT; loaderVersion = ver(c) }
            }
        }
        if (loader == Loader.FORGE && mc != null && isLegacy(mc)) loader = Loader.FORGE_LEGACY
        return GameProfile(mc, loader, loaderVersion)
    }

    fun isLegacy(mc: String): Boolean {
        val parts = mc.split('.')
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return parts.firstOrNull() == "1" && minor <= 12
    }
}

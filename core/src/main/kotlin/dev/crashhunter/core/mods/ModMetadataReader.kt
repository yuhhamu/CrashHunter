package dev.crashhunter.core.mods

import dev.crashhunter.core.model.Loader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object ModMetadataReader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_TEXT = 2_000_000
    private const val MAX_NESTED = 64L * 1024 * 1024

    fun read(file: File): ModJar {
        val enabled = !file.name.endsWith(".disabled")
        val fileName = file.name.removeSuffix(".disabled")
        return try {
            ZipFile(file).use { zip -> readZip(file, fileName, enabled, zip) }
        } catch (e: Exception) {
            ModJar(file, fileName, enabled, emptySet(), emptyList(), emptySet(), emptySet(), emptySet(), e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readZip(file: File, fileName: String, enabled: Boolean, zip: ZipFile): ModJar {
        fun text(name: String): String? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readNBytes(MAX_TEXT).toString(Charsets.UTF_8) } }
        val manifest = zip.getEntry("META-INF/MANIFEST.MF")?.let { e -> runCatching { zip.getInputStream(e).use { Manifest(it) } }.getOrNull() }
        val loaders = linkedSetOf<Loader>()
        val mods = mutableListOf<ModInfo>()
        val provides = linkedSetOf<String>()
        val mixins = linkedSetOf<String>()

        text("META-INF/mods.toml")?.let { t ->
            val parsed = parseModsToml(t, manifest)
            val loader = tomlLoader(parsed)
            mods += parsed.map { it.copy(loader = loader) }
            loaders += loader
        }
        text("META-INF/neoforge.mods.toml")?.let { t -> mods += parseModsToml(t, manifest).map { it.copy(loader = Loader.NEOFORGE) }; loaders += Loader.NEOFORGE }
        text("fabric.mod.json")?.let { t -> parseFabric(t, provides, mixins)?.let { mods += it.copy(loader = Loader.FABRIC); loaders += Loader.FABRIC } }
        text("quilt.mod.json")?.let { t -> parseQuilt(t)?.let { mods += it.copy(loader = Loader.QUILT); loaders += Loader.QUILT } }
        if (mods.isEmpty()) text("mcmod.info")?.let { t -> mods += parseMcmodInfo(t).map { it.copy(loader = Loader.FORGE_LEGACY) }; if (mods.isNotEmpty()) loaders += Loader.FORGE_LEGACY }

        val configs = linkedSetOf<String>()
        val missingRefmaps = linkedSetOf<String>()
        fun readConfig(cfg: String) {
            if (!configs.add(cfg)) return
            val t = text(cfg) ?: return
            mixinPackage(t)?.let(mixins::add)
            refmapOf(t)?.let { rm -> if (zip.getEntry(rm) == null) missingRefmaps += cfg }
        }
        manifest?.mainAttributes?.getValue("MixinConfigs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { readConfig(it) }
        for (e in zip.entries()) {
            if (!e.isDirectory && e.name.endsWith(".mixins.json") && !e.name.contains('/')) readConfig(e.name)
        }

        val packages = HashSet<String>()
        for (e in zip.entries()) {
            val n = e.name
            if (e.isDirectory || !n.endsWith(".class") || n.startsWith("META-INF/")) continue
            val slash = n.lastIndexOf('/')
            if (slash > 0) packages += n.substring(0, slash).replace('/', '.')
        }

        provides += mods.map { it.modId }
        provides += nestedModIds(zip)
        return ModJar(file, fileName, enabled, loaders, mods, provides, packages, mixins, mixinConfigs = configs, missingRefmaps = missingRefmaps)
    }

    private fun tomlLoader(parsed: List<ModInfo>): Loader {
        val deps = parsed.flatMap { it.dependencies }
        val forge = deps.any { it.modId.equals("forge", true) }
        val neo = deps.any { it.modId.equals("neoforge", true) && it.mandatory }
        return if (neo && !forge) Loader.NEOFORGE else Loader.FORGE
    }

    fun parseModsToml(text: String, manifest: Manifest? = null): List<ModInfo> {
        val result = runCatching { Toml.parse(text) }.getOrNull()
        if (result == null || result.hasErrors()) return fallbackToml(text)
        val implVersion = manifest?.mainAttributes?.getValue("Implementation-Version")
        val depsTable = result.getTable("dependencies")
        val mods = result.getArray("mods") ?: return fallbackToml(text)
        val list = mutableListOf<ModInfo>()
        for (i in 0 until mods.size()) {
            val t = mods.getTable(i) ?: continue
            val id = t.getString("modId") ?: continue
            var version = t.getString("version")
            if (version != null && version.contains("\${file.jarVersion}")) version = implVersion
            list += ModInfo(id, t.getString("displayName"), version, depsOf(depsTable, id))
        }
        return list.ifEmpty { fallbackToml(text) }
    }

    private fun depsOf(deps: TomlTable?, modId: String): List<ModDependency> {
        val arr: TomlArray = deps?.getArray(listOf(modId)) ?: return emptyList()
        val out = mutableListOf<ModDependency>()
        for (i in 0 until arr.size()) {
            val d = arr.getTable(i) ?: continue
            val id = d.getString("modId") ?: continue
            val type = d.getString("type")?.lowercase()
            val mandatory = when {
                type != null -> type == "required"
                else -> runCatching { d.getBoolean("mandatory") }.getOrNull() ?: false
            }
            out += ModDependency(id, mandatory, d.getString("versionRange"), incompatible = type == "incompatible")
        }
        return out
    }

    private fun fallbackToml(toml: String): List<ModInfo> {
        val ids = linkedSetOf<String>()
        var inMods = false
        for (raw in toml.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inMods = line.startsWith("[[mods]]"); continue }
            if (inMods && line.startsWith("modId")) Regex("""modId\s*=\s*["']([^"']+)["']""").find(line)?.let { ids += it.groupValues[1] }
        }
        return ids.map { ModInfo(it, null, null, emptyList()) }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    private fun predicates(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonArray -> e.mapNotNull { str(it) }.joinToString(" || ")
        else -> null
    }

    fun parseFabric(text: String, provides: MutableSet<String> = mutableSetOf(), mixins: MutableSet<String> = mutableSetOf()): ModInfo? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val id = str(o["id"]) ?: return null
        val deps = mutableListOf<ModDependency>()
        (o["depends"] as? JsonObject)?.forEach { (k, v) -> deps += ModDependency(k, true, predicates(v)) }
        (o["recommends"] as? JsonObject)?.forEach { (k, v) -> deps += ModDependency(k, false, predicates(v)) }
        (o["breaks"] as? JsonObject)?.forEach { (k, v) -> deps += ModDependency(k, false, predicates(v), incompatible = true) }
        (o["provides"] as? JsonArray)?.mapNotNull { str(it) }?.let(provides::addAll)
        (o["mixins"] as? JsonArray)?.forEach { m -> (str(m) ?: str((m as? JsonObject)?.get("config")))?.let { mixins += it.removeSuffix(".json") } }
        return ModInfo(id, str(o["name"]), str(o["version"]), deps)
    }

    private fun parseQuilt(text: String): ModInfo? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val loader = root["quilt_loader"] as? JsonObject ?: return null
        val id = str(loader["id"]) ?: return null
        val deps = (loader["depends"] as? JsonArray)?.mapNotNull { d ->
            when (d) {
                is JsonPrimitive -> d.contentOrNull?.let { ModDependency(it, true, null) }
                is JsonObject -> str(d["id"])?.let { ModDependency(it, str(d["optional"]) != "true", predicates(d["versions"])) }
                else -> null
            }
        } ?: emptyList()
        val meta = loader["metadata"] as? JsonObject
        return ModInfo(id, str(meta?.get("name")), str(loader["version"]), deps)
    }

    private fun parseMcmodInfo(text: String): List<ModInfo> {
        val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return regexMcmod(text)
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> (el["modList"] as? JsonArray) ?: JsonArray(listOf(el))
            else -> return emptyList()
        }
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = str(o["modid"]) ?: return@mapNotNull null
            val deps = (o["requiredMods"] as? JsonArray)?.mapNotNull { str(it) }?.map { ModDependency(it.substringBefore('@'), true, it.substringAfter('@', "").ifEmpty { null }) } ?: emptyList()
            ModInfo(id, str(o["name"]), str(o["version"]), deps)
        }
    }

    private fun regexMcmod(text: String) =
        Regex(""""modid"\s*:\s*"([^"]+)"""").findAll(text).map { ModInfo(it.groupValues[1], null, null, emptyList()) }.toList()

    private fun refmapOf(text: String): String? =
        runCatching { str(json.parseToJsonElement(text).jsonObject["refmap"]) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun mixinPackage(text: String): String? =
        runCatching { str(json.parseToJsonElement(text).jsonObject["package"]) }.getOrNull()

    private fun nestedModIds(zip: ZipFile): Set<String> {
        val ids = linkedSetOf<String>()
        for (e in zip.entries()) {
            val n = e.name
            if (e.isDirectory || !n.endsWith(".jar")) continue
            if (!(n.startsWith("META-INF/jarjar/") || n.startsWith("META-INF/jars/"))) continue
            if (e.size > MAX_NESTED) continue
            runCatching { zip.getInputStream(e).use { ids += modIdsOfStream(it) } }
        }
        return ids
    }

    private fun modIdsOfStream(input: InputStream): Set<String> {
        val ids = linkedSetOf<String>()
        ZipInputStream(input).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                val bytes = when (entry.name) {
                    "META-INF/mods.toml", "META-INF/neoforge.mods.toml", "fabric.mod.json", "quilt.mod.json" -> zin.readNBytes(MAX_TEXT)
                    else -> null
                }
                if (bytes != null) {
                    val t = bytes.toString(Charsets.UTF_8)
                    when (entry.name) {
                        "fabric.mod.json" -> { val p = mutableSetOf<String>(); parseFabric(t, p)?.let { ids += it.modId }; ids += p }
                        "quilt.mod.json" -> parseQuilt(t)?.let { ids += it.modId }
                        else -> ids += parseModsToml(t).map { it.modId }
                    }
                } else if (entry.name.endsWith(".jar") && (entry.name.startsWith("META-INF/jarjar/") || entry.name.startsWith("META-INF/jars/"))) {
                    val inner = zin.readNBytes(MAX_NESTED.toInt())
                    runCatching { ids += modIdsOfStream(ByteArrayInputStream(inner)) }
                }
                entry = zin.nextEntry
            }
        }
        return ids
    }
}

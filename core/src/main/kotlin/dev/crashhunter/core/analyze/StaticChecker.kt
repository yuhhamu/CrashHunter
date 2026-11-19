package dev.crashhunter.core.analyze

import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.model.Loader
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar
import dev.crashhunter.core.mods.PLATFORM_IDS
import dev.crashhunter.core.mods.Versions

object StaticChecker {
    fun check(index: ModIndex, profile: GameProfile): List<Finding> {
        val enabled = index.jars.filter { it.enabled }
        val enabledIndex = ModIndex(enabled)
        val out = mutableListOf<Finding>()

        for (j in dev.crashhunter.core.mods.CrashTools.find(enabledIndex)) out += Finding(Cause.CRASH_TOOL, j.displayName, j.fileName, jar = j.fileName)

        for (j in enabled) {
            j.readError?.let { out += Finding(Cause.CORRUPT_JAR, null, j.fileName, line = it, jar = j.fileName) }
        }

        enabled.flatMap { j -> j.mods.map { it.modId.lowercase() to j } }.groupBy({ it.first }, { it.second })
            .filter { (id, jars) -> id !in PLATFORM_IDS && jars.map { it.fileName }.distinct().size > 1 }
            .forEach { (id, jars) -> out += Finding(Cause.DUPLICATE_MOD, jars.first().displayName.ifBlank { id }, jars.joinToString(", ") { it.fileName }) }

        for (j in enabled) {
            if (!loaderFits(j, profile)) {
                out += Finding(Cause.WRONG_LOADER, j.displayName, j.fileName, required = j.loaders.joinToString("/") { it.display }, jar = j.fileName)
                continue
            }
            for (m in j.modsFor(profile.loader)) for (d in m.dependencies) {
                val id = d.modId.lowercase()
                if (d.incompatible) {
                    if (enabledIndex.byModId(id) != null) out += Finding(Cause.INCOMPATIBLE_MOD, j.displayName, enabledIndex.byModId(id)!!.displayName, jar = j.fileName)
                    continue
                }
                if (!d.mandatory) continue
                if (id == "minecraft") {
                    val mc = profile.mcVersion ?: continue
                    if (d.range != null && m.accepts(mc, d.range) == false) out += Finding(Cause.MC_VERSION, j.displayName, "Minecraft", d.range, mc, jar = j.fileName)
                    continue
                }
                if (id in setOf("forge", "neoforge", "fabricloader", "quilt_loader")) {
                    val lv = profile.loaderVersion ?: continue
                    val sameLoader = (id == "forge" && profile.loader in setOf(Loader.FORGE, Loader.FORGE_LEGACY)) || (id == "neoforge" && profile.loader == Loader.NEOFORGE) ||
                        (id == "fabricloader" && profile.loader == Loader.FABRIC) || (id == "quilt_loader" && profile.loader == Loader.QUILT)
                    if (sameLoader && d.range != null && m.accepts(lv, d.range) == false) out += Finding(Cause.LOADER_VERSION, j.displayName, d.modId, d.range, lv, jar = j.fileName)
                    continue
                }
                if (id in PLATFORM_IDS || id == "java" || id.startsWith("fabric-") && enabledIndex.byModId("fabric-api") != null) continue
                val provider = enabledIndex.byModId(id)
                if (provider == null) {
                    out += Finding(Cause.MISSING_DEPENDENCY, j.displayName, d.modId, d.range?.takeIf { it != "*" }, jar = j.fileName)
                } else {
                    val v = provider.modsFor(profile.loader).firstOrNull { it.modId.equals(id, true) }?.version ?: continue
                    if (d.range != null && m.accepts(v, d.range) == false) out += Finding(Cause.DEPENDENCY_VERSION, j.displayName, provider.displayName, d.range, v, jar = provider.fileName)
                }
            }
        }
        return out.distinctBy { "${it.cause}|${it.mod}|${it.other}" }
    }

    fun loaderFits(j: ModJar, profile: GameProfile): Boolean {
        if (j.loaders.isEmpty()) return true
        return when (profile.loader) {
            Loader.FORGE -> Loader.FORGE in j.loaders
            Loader.FORGE_LEGACY -> Loader.FORGE_LEGACY in j.loaders || Loader.FORGE in j.loaders
            Loader.NEOFORGE -> Loader.NEOFORGE in j.loaders || (Loader.FORGE in j.loaders && profile.mcVersion == "1.20.1")
            Loader.FABRIC -> Loader.FABRIC in j.loaders
            Loader.QUILT -> Loader.QUILT in j.loaders || Loader.FABRIC in j.loaders
            Loader.VANILLA, Loader.UNKNOWN -> true
        }
    }
}

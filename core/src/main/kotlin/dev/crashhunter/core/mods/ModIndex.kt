package dev.crashhunter.core.mods

import java.io.File

val PLATFORM_IDS = setOf(
    "minecraft", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mcp", "java",
    "fabricloader", "fabric-loader", "quilt_loader", "quilt-loader", "none",
)

class ModIndex(val jars: List<ModJar>) {
    private val byId: Map<String, ModJar>
    private val byFile = jars.associateBy { it.fileName.lowercase() }
    private val packageOwners: Map<String, ModJar>

    init {
        val ids = HashMap<String, ModJar>()
        val preferred = jars.filter { !it.enabled } + jars.filter { it.enabled }
        for (j in preferred.asReversed()) for (p in j.provides) ids.putIfAbsent(p.lowercase(), j)
        for (j in preferred) for (m in j.mods) ids[m.modId.lowercase()] = j
        byId = ids
        val owners = HashMap<String, ModJar>()
        for (j in jars) for (p in j.packages) {
            val prev = owners[p]
            if (prev == null || (prev != j && !ownsPackage(prev, p) && ownsPackage(j, p))) owners[p] = j
        }
        packageOwners = owners
    }

    private fun ownsPackage(j: ModJar, pkg: String) = j.mods.any { pkg.contains(it.modId.replace("-", "").replace("_", ""), ignoreCase = true) }

    fun byModId(id: String): ModJar? = byId[id.lowercase()]

    fun byFileName(name: String): ModJar? {
        val n = File(name).name.lowercase().removeSuffix(".disabled")
        return byFile[n] ?: byFile.entries.firstOrNull { (k, _) -> k.startsWith(n.removeSuffix(".jar")) }?.value
    }

    fun byClass(className: String): ModJar? {
        var pkg = className.substringBeforeLast('.', "")
        while (pkg.isNotEmpty()) {
            packageOwners[pkg]?.let { return it }
            pkg = pkg.substringBeforeLast('.', "")
        }
        return null
    }

    fun byMixinPackage(className: String): ModJar? =
        jars.firstOrNull { j -> j.mixinPackages.any { p -> p.isNotEmpty() && className.startsWith("$p.") } }

    fun byMixinConfig(config: String): ModJar? {
        val c = config.substringAfterLast('/').lowercase()
        return jars.firstOrNull { j -> j.enabled && j.mixinConfigs.any { it.substringAfterLast('/').lowercase() == c } }
            ?: jars.firstOrNull { j -> j.mixinConfigs.any { it.substringAfterLast('/').lowercase() == c } }
    }

    companion object {
        fun load(modsDir: File): ModIndex {
            val files = modsDir.listFiles { f -> f.isFile && (f.name.endsWith(".jar", true) || f.name.endsWith(".jar.disabled", true)) } ?: emptyArray()
            return ModIndex(files.sortedBy { it.name.lowercase() }.map { ModMetadataReader.read(it) })
        }
    }
}

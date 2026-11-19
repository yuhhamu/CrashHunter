package dev.crashhunter.core.mods

import dev.crashhunter.core.model.Loader
import java.io.File

data class ModDependency(
    val modId: String,
    val mandatory: Boolean,
    val range: String?,
    val incompatible: Boolean = false,
)

data class ModInfo(
    val modId: String,
    val name: String?,
    val version: String?,
    val dependencies: List<ModDependency>,
    val loader: Loader? = null,
) {
    fun accepts(version: String, range: String): Boolean? =
        if (loader == Loader.FABRIC || loader == Loader.QUILT) Versions.satisfiesFabric(version, range) else Versions.satisfiesMaven(version, range)
}

data class ModJar(
    val file: File,
    val fileName: String,
    val enabled: Boolean,
    val loaders: Set<Loader>,
    val mods: List<ModInfo>,
    val provides: Set<String>,
    val packages: Set<String>,
    val mixinPackages: Set<String>,
    val readError: String? = null,
    val mixinConfigs: Set<String> = emptySet(),
    val missingRefmaps: Set<String> = emptySet(),
) {
    val primaryId: String? get() = mods.firstOrNull()?.modId
    val displayName: String get() = mods.firstOrNull()?.name ?: primaryId ?: fileName
    fun modsFor(target: Loader?): List<ModInfo> {
        if (target == null || mods.none { it.loader != null }) return mods
        val order = when (target) {
            Loader.FORGE -> listOf(Loader.FORGE)
            Loader.FORGE_LEGACY -> listOf(Loader.FORGE_LEGACY, Loader.FORGE)
            Loader.NEOFORGE -> listOf(Loader.NEOFORGE, Loader.FORGE)
            Loader.FABRIC -> listOf(Loader.FABRIC)
            Loader.QUILT -> listOf(Loader.QUILT, Loader.FABRIC)
            Loader.VANILLA, Loader.UNKNOWN -> return mods
        }
        for (l in order) mods.filter { it.loader == l }.takeIf { it.isNotEmpty() }?.let { return it }
        return mods
    }

    override fun toString(): String = fileName
}

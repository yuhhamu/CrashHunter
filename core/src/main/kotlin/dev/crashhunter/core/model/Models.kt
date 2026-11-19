package dev.crashhunter.core.model

import java.io.File

enum class Loader(val display: String) {
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    FORGE_LEGACY("Forge (1.12-)"),
    VANILLA("Vanilla"),
    UNKNOWN("?");
}

data class GameProfile(
    val mcVersion: String?,
    val loader: Loader,
    val loaderVersion: String?,
) {
    val label: String get() = listOfNotNull(mcVersion, loader.display.takeIf { loader != Loader.VANILLA }, loaderVersion).joinToString(" ")
}

data class GameInstance(
    val id: String,
    val name: String,
    val dir: File,
    val gameDir: File,
    val profile: GameProfile,
    val launcher: String = "prism",
) {
    val modsDir: File get() = File(gameDir, "mods")
    override fun toString(): String = name
}

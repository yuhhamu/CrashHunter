package dev.crashhunter.core

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.StaticChecker
import dev.crashhunter.core.fix.FixEnvironment
import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.fix.FixPlanner
import dev.crashhunter.core.model.Loader
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModMetadataReader
import dev.crashhunter.core.prism.InstanceScanner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiLoaderMetadataTest {
    private fun tmp() = Files.createTempDirectory("chml").toFile()

    private fun toml(id: String, name: String, version: String, deps: List<Triple<String, Boolean, String>>) = buildString {
        appendLine("modLoader=\"javafml\"")
        appendLine("loaderVersion=\"[47,)\"")
        appendLine("license=\"MIT\"")
        appendLine("[[mods]]")
        appendLine("modId=\"$id\"")
        appendLine("version=\"$version\"")
        appendLine("displayName=\"$name\"")
        for ((dep, mandatory, range) in deps) {
            appendLine("[[dependencies.$id]]")
            appendLine("modId=\"$dep\"")
            appendLine("mandatory=$mandatory")
            appendLine("versionRange=\"$range\"")
        }
    }

    private fun profileDir(root: File): File = TestJars.instance(root, "inst", loaderVersion = "47.4.28")

    @Test
    fun `jars carrying several loaders are judged by the metadata of the instance loader`() {
        val root = tmp()
        val dir = profileDir(root)
        val mods = File(dir, "minecraft/mods")
        TestJars.jar(File(mods, "collective.jar"), mapOf(
            "META-INF/mods.toml" to toml("collective", "Collective", "8.81", listOf(Triple("minecraft", true, "[1.20.1]"))),
            "fabric.mod.json" to """{"schemaVersion":1,"id":"collective","version":"8.81","depends":{"fabricloader":">=0.12.6","minecraft":">=1.20"}}""",
        ))
        TestJars.jar(File(mods, "dh.jar"), mapOf(
            "META-INF/mods.toml" to toml("distanthorizons", "Distant Horizons", "3.3.3", listOf(Triple("forge", true, "[0,)"), Triple("minecraft", true, "[1.20],[1.20.1]"))),
            "fabric.mod.json" to """{"schemaVersion":1,"id":"distanthorizons","version":"3.3.3","depends":{"minecraft":["1.20","1.20.1"]}}""",
        ))
        TestJars.jar(File(mods, "berezka.jar"), mapOf(
            "META-INF/mods.toml" to toml("berezka_api", "Berezka API", "1.2.9.7.1", listOf(Triple("forge", true, "[47,)"), Triple("minecraft", true, "[1.20.1,1.21)"))),
            "META-INF/neoforge.mods.toml" to toml("berezka_api", "Berezka API", "1.2.9.7.1", listOf(Triple("neoforge", true, "[21,)"), Triple("minecraft", true, "[1.21,1.22)"))),
        ))
        TestJars.jar(File(mods, "packetfixer.jar"), mapOf(
            "META-INF/mods.toml" to toml("packetfixer", "Packet Fixer", "3.3.2", listOf(Triple("forge", false, "*"), Triple("neoforge", false, "*"), Triple("minecraft", true, "[1.18,1.20.4]"))),
            "META-INF/neoforge.mods.toml" to toml("packetfixer", "Packet Fixer", "3.3.2", listOf(Triple("minecraft", true, "[1.18,1.20.4]"))),
            "fabric.mod.json" to """{"schemaVersion":1,"id":"packetfixer","version":"3.3.2","depends":{"minecraft":["1.18","1.20.1"]}}""",
        ))
        TestJars.jar(File(mods, "plugin.jar"), mapOf(
            "META-INF/mods.toml" to toml("plugin", "Plugin", "1.0", listOf(Triple("minecraft", true, "[1.20.1,1.21)"), Triple("berezka_api", true, "[1.2.8.3,)"))),
        ))
        val inst = InstanceScanner.read(dir)
        val findings = StaticChecker.check(ModIndex.load(mods), inst.profile)
        assertTrue(findings.none { it.cause in setOf(Cause.MC_VERSION, Cause.WRONG_LOADER, Cause.MISSING_DEPENDENCY, Cause.DEPENDENCY_VERSION) }, findings.toString())

        val berezka = ModMetadataReader.read(File(mods, "berezka.jar"))
        assertEquals(Loader.FORGE, berezka.modsFor(Loader.FORGE).single().loader)
        assertEquals(Loader.NEOFORGE, berezka.modsFor(Loader.NEOFORGE).single().loader)
        assertTrue(Loader.FORGE in ModMetadataReader.read(File(mods, "packetfixer.jar")).loaders)
    }

    @Test
    fun `a jar made for another Minecraft version is still reported`() {
        val root = tmp()
        val dir = profileDir(root)
        val mods = File(dir, "minecraft/mods")
        TestJars.jar(File(mods, "old.jar"), mapOf(
            "META-INF/mods.toml" to toml("old", "Old", "1.0", listOf(Triple("minecraft", true, "[1.18.2,1.19)"))),
            "fabric.mod.json" to """{"schemaVersion":1,"id":"old","version":"1.0","depends":{"minecraft":"1.18.2"}}""",
        ))
        val findings = StaticChecker.check(ModIndex.load(mods), InstanceScanner.read(dir).profile)
        assertTrue(findings.any { it.cause == Cause.MC_VERSION }, findings.toString())
    }

    @Test
    fun `duplicates keep the jar made for this Minecraft version and leave different mods to the user`() = runBlocking {
        val root = tmp()
        val dir = profileDir(root)
        val mods = File(dir, "minecraft/mods")
        TestJars.jar(File(mods, "xlpackets-1.18.2-2.1.jar"), mapOf("META-INF/mods.toml" to toml("xlpackets", "XL Packets", "2.1", emptyList())))
        TestJars.jar(File(mods, "xlpackets-forge-1.20.1-1.0.5.jar"), mapOf("META-INF/mods.toml" to toml("xlpackets", "XXL Packets", "1.0.5", emptyList())))
        TestJars.jar(File(mods, "Fusion-1.20.1-6.0.0.3.jar"), mapOf("META-INF/mods.toml" to toml("fusion", "SimpleOres:Fusion", "1.20.1-6.0.0.3", listOf(Triple("minecraft", true, "[1.20.1,1.21)")))))
        TestJars.jar(File(mods, "fusion-1.3.16-forge-mc1.20.1.jar"), mapOf("META-INF/mods.toml" to toml("fusion", "Fusion", "1.3.16", listOf(Triple("minecraft", true, "[1.20.1,1.21)")))))
        TestJars.jar(File(mods, "pots-13.0.0.jar"), mapOf("META-INF/mods.toml" to toml("botanypots", "Botany Pots", "13.0.0", emptyList())))
        TestJars.jar(File(mods, "pots-13.0.43.jar"), mapOf("META-INF/mods.toml" to toml("botanypots", "Botany Pots", "13.0.43", emptyList())))
        val inst = InstanceScanner.read(dir)
        val plan = FixPlanner(setOf(FixKind.DUPLICATE), FixEnvironment(16384, null, emptyList())).plan(inst, ModIndex.load(mods), emptyList())
            .filter { it.kind == FixKind.DUPLICATE }
        val xl = plan.single { it.params["files"]!!.contains("xlpackets") }
        assertEquals("xlpackets-forge-1.20.1-1.0.5.jar", xl.params["keep"])
        assertEquals("1", xl.params["auto"])
        assertEquals("0", plan.single { it.params["files"]!!.contains("usion") }.params["auto"])
        val pots = plan.single { it.params["files"]!!.contains("pots") }
        assertEquals("pots-13.0.43.jar", pots.params["keep"])
        assertEquals("1", pots.params["auto"])
    }
}

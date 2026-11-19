package dev.crashhunter.core

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.CrashReportParser
import dev.crashhunter.core.analyze.CulpritScorer
import dev.crashhunter.core.analyze.StaticChecker
import dev.crashhunter.core.bisect.ModToggler
import dev.crashhunter.core.bisect.WorkCopy
import dev.crashhunter.core.model.Loader
import dev.crashhunter.core.mods.DependencyGraph
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.Versions
import dev.crashhunter.core.prism.InstanceScanner
import dev.crashhunter.core.prism.PrismLocator
import dev.crashhunter.core.util.Redactor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModsAndPrismTest {
    private fun tmp(): File = Files.createTempDirectory("ch").toFile()

    @Test
    fun `reads forge and fabric metadata with dependencies`() {
        val d = tmp()
        TestJars.forgeMod(d, "a.jar", "alpha", deps = TestJars.dep("alpha", "beta", "[2.0,)"))
        TestJars.fabricMod(d, "f.jar", "fab", """{"fabricloader":">=0.14","cloth-config":"*"}""")
        val idx = ModIndex.load(d)
        val a = idx.byModId("alpha")!!
        assertEquals(setOf(Loader.FORGE), a.loaders)
        assertTrue(a.mods.single().dependencies.any { it.modId == "beta" && it.mandatory && it.range == "[2.0,)" })
        assertEquals("a.jar", idx.byClass("com.example.alpha.Main")?.fileName)
        assertEquals(setOf(Loader.FABRIC), idx.byModId("fab")!!.loaders)
    }

    @Test
    fun `static check finds missing, outdated, duplicate and wrong loader`() {
        val d = tmp()
        TestJars.forgeMod(d, "a.jar", "alpha", deps = TestJars.dep("alpha", "beta", "[2.0,)") + "\n" + TestJars.dep("alpha", "gamma", "[1,)"))
        TestJars.forgeMod(d, "b.jar", "beta", version = "1.5")
        TestJars.forgeMod(d, "c1.jar", "charlie")
        TestJars.forgeMod(d, "c2.jar", "charlie", version = "2.0")
        TestJars.fabricMod(d, "f.jar", "fab")
        val profile = InstanceScanner.readProfile(File(TestJars.instance(tmp(), "x"), "mmc-pack.json"))
        val f = StaticChecker.check(ModIndex.load(d), profile)
        val causes = f.map { it.cause }.toSet()
        assertTrue(Cause.MISSING_DEPENDENCY in causes, f.toString())
        assertTrue(Cause.DEPENDENCY_VERSION in causes, f.toString())
        assertTrue(Cause.DUPLICATE_MOD in causes, f.toString())
        assertTrue(Cause.WRONG_LOADER in causes, f.toString())
        assertFalse(Cause.MC_VERSION in causes, f.toString())
    }

    @Test
    fun `a dependency points to the enabled jar when a disabled copy has the same mod id`() {
        val d = tmp()
        TestJars.forgeMod(d, "tadv-3.0.jar", "tinkers_advanced")
        TestJars.forgeMod(d, "tadv_core-3.0.jar.disabled", "tinkers_advanced", pkg = "com.example.core")
        TestJars.forgeMod(d, "materials.jar", "materials", deps = TestJars.dep("materials", "tinkers_advanced", "[1,)"))
        val idx = ModIndex.load(d)
        assertEquals("tadv-3.0.jar", idx.byModId("tinkers_advanced")!!.fileName)
        assertEquals(setOf("materials.jar", "tadv-3.0.jar"), DependencyGraph(idx).withRequirements(listOf("materials.jar")))
    }

    @Test
    fun `dependency graph closures`() {
        val d = tmp()
        TestJars.forgeMod(d, "lib.jar", "lib")
        TestJars.forgeMod(d, "mid.jar", "mid", deps = TestJars.dep("mid", "lib", "[1,)"))
        TestJars.forgeMod(d, "top.jar", "top", deps = TestJars.dep("top", "mid", "[1,)"))
        TestJars.forgeMod(d, "other.jar", "other")
        val g = DependencyGraph(ModIndex.load(d))
        assertEquals(setOf("top.jar", "mid.jar", "lib.jar"), g.withRequirements(listOf("top.jar")))
        assertEquals(setOf("lib.jar", "mid.jar", "top.jar"), g.withDependents(listOf("lib.jar")))
    }

    @Test
    fun `versions`() {
        assertEquals(true, Versions.satisfiesMaven("47.4.6", "[47,)"))
        assertEquals(false, Versions.satisfiesMaven("1.5", "[2.0,)"))
        assertEquals(true, Versions.satisfiesMaven("1.20.1", "[1.20.1,1.21)"))
        assertEquals(false, Versions.satisfiesMaven("1.21", "[1.20.1,1.21)"))
        assertEquals(true, Versions.satisfiesFabric("0.15.11", ">=0.14"))
        assertEquals(true, Versions.satisfiesFabric("1.20.1", "1.20.x"))
        assertEquals(false, Versions.satisfiesFabric("1.19.4", "~1.20"))
    }

    @Test
    fun `scanner and locator read prism layout`() {
        val data = tmp()
        File(data, "prismlauncher.cfg").writeText("[General]\nInstanceDir=inst\n")
        val inst = File(data, "inst").also { it.mkdirs() }
        TestJars.instance(inst, "pack", loaderUid = "net.neoforged", loaderVersion = "21.1.100", mc = "1.21.1")
        assertEquals(inst.absolutePath, PrismLocator.instancesDirOf(data).absolutePath)
        val list = InstanceScanner.scan(inst)
        assertEquals(1, list.size)
        assertEquals(Loader.NEOFORGE, list[0].profile.loader)
        assertEquals("1.21.1", list[0].profile.mcVersion)
    }

    @Test
    fun `work copy links mods and never touches the original`() {
        val root = tmp()
        val dir = TestJars.instance(root, "pack")
        val mods = File(dir, "minecraft/mods")
        TestJars.forgeMod(mods, "a.jar", "alpha")
        TestJars.forgeMod(mods, "b.jar", "beta")
        File(dir, "minecraft/config").mkdirs(); File(dir, "minecraft/config/x.toml").writeText("a=1")
        File(dir, "minecraft/saves/w").mkdirs(); File(dir, "minecraft/saves/w/level.dat").writeText("w")
        val original = InstanceScanner.read(dir)
        val copy = WorkCopy.create(original, includeSaves = false)
        assertTrue(File(copy.modsDir, "a.jar").isFile)
        assertTrue(File(copy.gameDir, "config/x.toml").isFile)
        assertFalse(File(copy.gameDir, "saves").exists())
        assertTrue(copy.name.endsWith("(CrashHunter)"))
        val journal = File(tmp(), "j.json")
        val t = ModToggler(copy.modsDir, journal)
        t.begin(copy.id, true)
        t.apply(listOf("a.jar", "b.jar"), setOf("b.jar"))
        assertTrue(File(copy.modsDir, "a.jar.disabled").isFile)
        assertTrue(File(mods, "a.jar").isFile)
        assertEquals(1, ModToggler.restore(journal))
        assertTrue(File(copy.modsDir, "a.jar").isFile)
        assertFalse(journal.exists())
        assertEquals(listOf("pack"), InstanceScanner.scan(root).map { it.id })
        WorkCopy.delete(WorkCopy.dirFor(original))
        assertFalse(WorkCopy.dirFor(original).exists())
    }

    @Test
    fun `stack frames map to jars through the index`() {
        val d = tmp()
        TestJars.forgeMod(d, "ff.jar", "fourfoldfactory", pkg = "com.example.fourfoldfactory.node")
        val text = """
            Description: Ticking block entity

            java.lang.IllegalArgumentException: amount must be positive: 0
            	at com.example.fourfoldfactory.node.ResourceAmount.<init>(ResourceAmount.java:10) ~[%23196!/:?] {re:classloading}
            	at net.minecraft.world.level.Level.tickBlockEntities(Level.java:468) ~[forge-1.20.1.jar%23191!/:?] {}
        """.trimIndent()
        val p = CrashReportParser.parse(text)
        val s = CulpritScorer.score(p, emptyList(), ModIndex.load(d))
        assertEquals("ff.jar", s.first().jar)
    }

    @Test
    fun `redactor hides personal data`() {
        val r = Redactor.redact("C:\\Users\\taro\\AppData x --accessToken abc.def --username Taro 192.168.1.5 jar-1.2.3.4.jar")
        assertFalse(r.contains("taro")); assertFalse(r.contains("abc.def")); assertFalse(r.contains("Taro")); assertFalse(r.contains("192.168"))
        assertTrue(r.contains("1.2.3.4.jar"))
        assertNotNull(r)
    }

    @Test
    fun `reads Prism install folders from reg query output`() {
        val out = """
HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\PrismLauncher
    DisplayName    REG_SZ    Prism Launcher
    InstallLocation    REG_SZ    D:\Users\me\AppData\Local\Programs\PrismLauncher
    UninstallString    REG_SZ    "E:\Games\PrismLauncher\uninstall.exe"
    DisplayIcon    REG_SZ    C:\Other\app.exe,0
"""
        val dirs = PrismLocator.parseRegistryDirs(out).map { it.path }
        assertEquals(listOf("D:\\Users\\me\\AppData\\Local\\Programs\\PrismLauncher", "E:\\Games\\PrismLauncher"), dirs)
    }

    @Test
    fun `after a bisect the solutions leave out the bisect hint`() {
        val kb = dev.crashhunter.core.analyze.SolutionKb.load("ja")
        val r = kb.render(Cause.values().first { c -> kb.render(c, mapOf("mod" to "X")).solutions.any { "原因特定" in it } }, mapOf("mod" to "X"))
        assertTrue(r.solutions.any { "原因特定" in it })
        assertFalse(r.afterBisect.any { "原因特定" in it })
    }
}

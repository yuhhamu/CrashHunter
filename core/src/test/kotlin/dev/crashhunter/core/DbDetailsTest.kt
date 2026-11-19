package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.analyze.SolutionKb
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.db.CrashDb
import dev.crashhunter.core.db.CrashItem
import dev.crashhunter.core.db.DbDetails
import dev.crashhunter.core.db.DbEnv
import dev.crashhunter.core.db.DbReport
import dev.crashhunter.core.db.DbRefs
import dev.crashhunter.core.mods.ModIndex
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DbDetailsTest {
    private val report = """
---- Minecraft Crash Report ----
// Who set us up the TNT?

Time: 2026-11-19 10:00:00
Description: Rendering overlay

java.lang.IllegalStateException: Bad state at C:\Users\someuser\AppData\x.json
	at com.example.alpha.client.Renderer.draw(Renderer.java:42) ~[alpha-1.0.jar%23101!/:?] {re:classloading}
	at TRANSFORMER/minecraft@1.20.1/net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:900) ~[client-1.20.1.jar:?] {}
Caused by: java.lang.NullPointerException: Cannot invoke "Object.toString()"
	at com.example.alpha.Util.name(Util.java:7) ~[alpha-1.0.jar%23101!/:?] {}


A detailed walkthrough of the error, its code path and all known details is as follows:
---------------------------------------------------------------------------------------

-- System Details --
Details:
	Minecraft Version: 1.20.1
	Operating System: Windows 11 (amd64) version 10.0
	Java Version: 17.0.8, Microsoft
	Memory: 1000 bytes (1 MiB) / 2000 bytes (2 MiB) up to 8589934592 bytes (8192 MiB)
	Graphics card #0 name: NVIDIA GeForce RTX 3070
	Graphics card #0 versionInfo: DriverVersion=31.0.15.3742
	Backend library: LWJGL version 3.3.1 build 7
""".trimIndent()

    @Test
    fun `crash details keep frames causes and system but no user paths`() {
        val dir = Files.createTempDirectory("chdet").toFile()
        val mods = File(dir, "mods").apply { mkdirs() }
        TestJars.forgeMod(mods, "alpha-1.0.jar", "alpha", pkg = "com.example.alpha")
        val crash = File(dir, "crash.txt").apply { writeText(report) }
        val index = ModIndex.load(mods)
        val d = DiagnosisService(SolutionKb.load("ja", null)).diagnose(CrashSource(SourceKind.CRASH_REPORT, crash, crash.lastModified()), null, index)
        val item = assertNotNull(DbDetails.crash(d, index, "ingame", 2))
        assertEquals("Rendering overlay", item.phase)
        assertEquals("java.lang.IllegalStateException", item.ex)
        assertEquals("Renderer.java:42", item.frames.first().src)
        assertEquals("alpha", item.frames.first().mod)
        assertEquals("java.lang.NullPointerException", item.causes.single().ex)
        assertEquals("NVIDIA GeForce RTX 3070", item.sys["Graphics card #0 name"])
        assertTrue(item.mods.any { it.id == "alpha" && it.cf != null && it.sha1 != null })
        assertEquals(2, item.seen)
        val r = DbReport(install = "id", app = "t", time = 1, env = DbEnv("1.20.1", "forge", os = DbRefs.osName()), items = listOf(item))
        val json = CrashDb(dir, null, { "" }, { null }).encode(r)
        assertFalse(json.contains("someuser"))
        assertFalse(json.contains(dir.absolutePath.replace("\\", "\\\\")))
        assertTrue(json.contains("\"type\":\"crash\""))
        assertTrue(json.contains("\"schema\":1"))
        assertTrue((CrashDb(dir, null, { "" }, { null }).decode(json).items.single() as CrashItem).frames.isNotEmpty())
    }

    @Test
    fun `exception names and refs stay within what the server accepts`() {
        assertEquals(null, DbRefs.exceptionName(dev.crashhunter.core.analyze.CrashFingerprint("cause:MOD_LOAD_ERROR")))
        assertEquals("MixinApplyError", DbRefs.exceptionName(dev.crashhunter.core.analyze.CrashFingerprint("org.spongepowered.asm.mixin.throwables.MixinApplyError")))
        val dir = Files.createTempDirectory("chref").toFile()
        val jar = TestJars.forgeMod(dir, "Odd Name [1.20.1].jar", "oddmod", version = "1.0 (beta)")
        val ref = DbRefs.of(ModIndex.load(dir).jars.single())
        assertEquals("oddmod", ref.id)
        assertEquals(null, ref.v)
        assertTrue(jar.isFile)
    }
}

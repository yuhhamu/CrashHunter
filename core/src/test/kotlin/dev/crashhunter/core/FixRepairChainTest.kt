package dev.crashhunter.core

import com.sun.net.httpserver.HttpServer
import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.Finding
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.fix.DisableMod
import dev.crashhunter.core.fix.FixApplier
import dev.crashhunter.core.fix.FixEnvironment
import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.fix.FixPlanner
import dev.crashhunter.core.fix.FixProposal
import dev.crashhunter.core.fix.FixSession
import dev.crashhunter.core.fix.FixState
import dev.crashhunter.core.fix.Replan
import dev.crashhunter.core.fix.Replanner
import dev.crashhunter.core.fix.ReplaceMod
import dev.crashhunter.core.fix.TrialFactory
import dev.crashhunter.core.fix.VerifyResult
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.fix.remote.ModrinthClient
import dev.crashhunter.core.fix.remote.PrismMetaClient
import dev.crashhunter.core.fix.remote.RemoteFetcherImpl
import dev.crashhunter.core.fix.remote.RemoteResolverImpl
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.prism.InstanceScanner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixRepairChainTest {
    private fun tmp() = Files.createTempDirectory("chrepair").toFile()
    private fun env(root: File) = FixEnvironment(16384, null, listOf(File(root, "javas")))

    private fun emx(file: File, version: String, refmap: Boolean): File {
        val entries = mutableMapOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nMixinConfigs: emextras.mixins.json\n",
            "META-INF/mods.toml" to "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"x\"\n[[mods]]\nmodId=\"emextras\"\nversion=\"$version\"\ndisplayName=\"Evolved Mekanism Extras\"\n",
            "emextras.mixins.json" to "{\"package\":\"io.github.emx.mixin\",\"refmap\":\"emextras.refmap.json\",\"mixins\":[]}",
            "io/github/emx/E$version.class".replace(".", "_").replace("_class", ".class") to "x",
        )
        if (refmap) entries["emextras.refmap.json"] = "{}"
        return TestJars.jar(file, entries)
    }

    @Test
    fun `broken build is replaced by an older working version that dependents accept`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        val broken = emx(File(mods, "emx-1.5.1-all.jar"), "1.5.1", refmap = false)
        TestJars.forgeMod(mods, "astral.jar", "astral", deps = TestJars.dep("astral", "emextras", "[1.4.1,)"))
        val srv = File(root, "srv")
        val v150 = emx(File(srv, "emx-1.5.0-all.jar"), "1.5.0", refmap = false)
        val v141 = emx(File(srv, "emx-1.4.1.jar"), "1.4.1", refmap = true)
        val v130 = emx(File(srv, "emx-1.3.0.jar"), "1.3.0", refmap = true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val host = "http://127.0.0.1:${server.address.port}"
            fun ver(id: String, n: String, f: File, date: String) =
                """{"id":"$id","project_id":"P","version_number":"$n","version_type":"release","date_published":"$date","files":[{"url":"$host/f/${f.name}","filename":"${f.name}","primary":true,"hashes":{"sha1":"${Http.hash(f, "SHA-1")}"}}]}"""
            val bytes: ByteArray? = when {
                path == "/v2/version_file/${Http.hash(broken, "SHA-1")}" -> ver("V151", "1.5.1", broken, "2026-11-01").toByteArray()
                path == "/v2/project/P/version" -> ("[" + listOf(ver("V151", "1.5.1", broken, "2026-11-01"), ver("V150", "1.5.0", v150, "2026-07-20"),
                    ver("V130", "1.3.0", v130, "2026-04-01"), ver("V141", "1.4.1", v141, "2026-05-25")).joinToString(",") + "]").toByteArray()
                path.startsWith("/f/") -> File(srv, path.removePrefix("/f/")).readBytes()
                else -> null
            }
            if (bytes == null) { ex.sendResponseHeaders(404, -1); ex.close() } else { ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        }
        server.start()
        try {
            val http = Http("test")
            val host = "http://127.0.0.1:${server.address.port}"
            val resolver = RemoteResolverImpl(ModrinthClient(http, "$host/v2"), null, null, PrismMetaClient(http, "$host/meta"))
            val inst = InstanceScanner.read(dir)
            val d = Diagnosis(CrashSource(SourceKind.CRASH_REPORT, null, 0), Cause.MIXIN_NO_REFMAP,
                listOf(Finding(Cause.MIXIN_NO_REFMAP, "emextras", "x.Y", jar = broken.name, config = "emextras.mixins.json")), emptyList(), "", "", emptyList(), null, null)
            val plan = FixPlanner(setOf(FixKind.REPLACE_BROKEN, FixKind.CORRUPT_JAR), env(root), resolver).plan(inst, ModIndex.load(mods), listOf(d))
            val p = plan.single()
            assertEquals(FixKind.REPLACE_BROKEN, p.kind)
            val action = p.actions.single() as ReplaceMod
            assertTrue(action.needRefmap)
            assertEquals(listOf("[1.4.1,)"), action.ranges)
            val applier = FixApplier(RemoteFetcherImpl(http, null, null, File(root, "dl")), File(root, "fixes"))
            val rec = applier.apply(inst, plan)
            assertTrue(File(mods, "emx-1.4.1.jar").isFile)
            assertFalse(File(mods, broken.name).exists())
            applier.undo(rec)
            assertTrue(File(mods, broken.name).isFile)
            assertFalse(File(mods, "emx-1.4.1.jar").exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chain keeps fixing until the game starts and can undo every round`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        TestJars.forgeMod(mods, "a.jar", "a")
        TestJars.forgeMod(mods, "b.jar", "b")
        val inst = InstanceScanner.read(dir)
        val applier = FixApplier(null, File(root, "fixes"))
        var launches = 0
        val trials = TrialFactory { _, _, _ ->
            {
                launches++
                if (File(mods, "b.jar").exists()) TrialOutcome(TrialResult.FAIL, CrashFingerprint("b-broke", message = "B"), "b") else TrialOutcome(TrialResult.PASS)
            }
        }
        val replanner = Replanner { _, outcome ->
            if (outcome.fingerprint?.exception == "b-broke") Replan(listOf(FixProposal("CORRUPT_JAR:b.jar", FixKind.CORRUPT_JAR, "b", actions = listOf(DisableMod("b.jar")))), outcome.fingerprint) else null
        }
        val first = listOf(FixProposal("CORRUPT_JAR:a.jar", FixKind.CORRUPT_JAR, "a", actions = listOf(DisableMod("a.jar"))))
        val out = FixSession(applier, trials, { null }).runChain(inst, first, CrashFingerprint("a-broke", message = "A"), true, 5, replanner)
        assertEquals(VerifyResult.FIXED, out.verify)
        assertEquals(2, out.rounds)
        assertEquals(2, launches)
        assertEquals(listOf(FixState.KEPT_NEW_FAILURE, FixState.VERIFIED), out.records.map { it.state })
        assertTrue(out.records.all { it.chainId != null && it.chainId == out.records.first().chainId })
        assertFalse(File(mods, "a.jar").exists()); assertFalse(File(mods, "b.jar").exists())
        applier.undoAll(out.records)
        assertTrue(File(mods, "a.jar").isFile); assertTrue(File(mods, "b.jar").isFile)
    }

    @Test
    fun `chain stops at the round limit`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        (1..4).forEach { TestJars.forgeMod(mods, "m$it.jar", "m$it") }
        val inst = InstanceScanner.read(dir)
        var n = 0
        val trials = TrialFactory { _, _, _ -> { n++; TrialOutcome(TrialResult.FAIL, CrashFingerprint("e$n", message = "X$n")) } }
        val replanner = Replanner { _, _ ->
            val next = (1..4).map { "m$it.jar" }.firstOrNull { File(mods, it).exists() } ?: return@Replanner null
            Replan(listOf(FixProposal("CORRUPT_JAR:$next", FixKind.CORRUPT_JAR, next, actions = listOf(DisableMod(next)))), null)
        }
        val out = FixSession(FixApplier(null, File(root, "fixes")), trials, { null })
            .runChain(inst, listOf(FixProposal("CORRUPT_JAR:m1.jar", FixKind.CORRUPT_JAR, "m1", actions = listOf(DisableMod("m1.jar")))), CrashFingerprint("e0", message = "X0"), true, 3, replanner)
        assertEquals(3, out.rounds)
        assertEquals(3, n)
        assertTrue(File(mods, "m4.jar").isFile)
    }

    @Test
    fun `disabled jar that has the missing class is enabled and its duplicate disabled`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        TestJars.jar(File(mods, "adv-core-5.jar.disabled"), mapOf(
            "META-INF/mods.toml" to "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"x\"\n[[mods]]\nmodId=\"tinkers_advanced\"\nversion=\"3.0.0-beta.5\"\n",
            "com/c2h6s/tinkers_advanced/core/library/registry/SimpleMaterialWrappedRegister.class" to "x"))
        TestJars.jar(File(mods, "adv-13fix.jar"), mapOf(
            "META-INF/mods.toml" to "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"x\"\n[[mods]]\nmodId=\"tinkers_advanced\"\nversion=\"3.0.0-beta.13fix\"\n",
            "com/c2h6s/tinkers_advanced/content/A.class" to "x"))
        val inst = InstanceScanner.read(dir)
        val d = Diagnosis(CrashSource(SourceKind.CRASH_REPORT, null, 0), Cause.CLASS_MISMATCH,
            listOf(Finding(Cause.CLASS_MISMATCH, "tinkers_advanced_materials", "com.c2h6s.tinkers_advanced.core.library.registry.SimpleMaterialWrappedRegister")), emptyList(), "", "", emptyList(), null, null)
        val plan = FixPlanner(setOf(FixKind.ENABLE_PROVIDER), env(root)).plan(inst, ModIndex.load(mods), listOf(d))
        val p = plan.single()
        assertEquals(FixKind.ENABLE_PROVIDER, p.kind)
        val rec = FixApplier(null, File(root, "fixes")).apply(inst, plan)
        assertTrue(File(mods, "adv-core-5.jar").isFile)
        assertTrue(File(mods, "adv-13fix.jar.disabled").isFile)
        FixApplier(null, File(root, "fixes")).undo(rec)
        assertTrue(File(mods, "adv-core-5.jar.disabled").isFile)
        assertTrue(File(mods, "adv-13fix.jar").isFile)
    }

    @Test
    fun `database fixes are tried first and the rest follows when they fail`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        TestJars.forgeMod(mods, "a.jar", "a")
        TestJars.forgeMod(mods, "b.jar", "b")
        val inst = InstanceScanner.read(dir)
        val seen = mutableListOf<Set<String>>()
        val crash = CrashFingerprint("b-broke", message = "B")
        val trials = TrialFactory { _, _, _ ->
            {
                seen += (mods.list() ?: emptyArray()).filter { it.endsWith(".jar") }.toSet()
                if (File(mods, "b.jar").exists()) TrialOutcome(TrialResult.FAIL, crash) else TrialOutcome(TrialResult.PASS)
            }
        }
        val db = FixProposal("DBFIX:a.jar", FixKind.DB_FIX, "a", mapOf("db" to "1"), listOf(DisableMod("a.jar")))
        val normal = FixProposal("CORRUPT_JAR:b.jar", FixKind.CORRUPT_JAR, "b", actions = listOf(DisableMod("b.jar")))
        val out = FixSession(FixApplier(null, File(root, "fixes")), trials, { null })
            .runChain(inst, listOf(normal, db), crash, true, 5, Replanner { _, _ -> null })
        assertEquals(VerifyResult.FIXED, out.verify)
        assertEquals(listOf(setOf("b.jar"), setOf("a.jar")), seen)
        assertEquals(listOf(FixState.UNDONE, FixState.VERIFIED), out.records.map { it.state })
        assertTrue(File(mods, "a.jar").isFile)
        assertFalse(File(mods, "b.jar").exists())
    }
}

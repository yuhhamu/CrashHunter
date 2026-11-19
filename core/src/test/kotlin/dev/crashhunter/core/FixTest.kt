package dev.crashhunter.core

import com.sun.net.httpserver.HttpServer
import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.Finding
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.fix.AddMod
import dev.crashhunter.core.fix.DisableMod
import dev.crashhunter.core.fix.FixApplier
import dev.crashhunter.core.fix.FixEnvironment
import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.fix.FixPlanner
import dev.crashhunter.core.fix.FixState
import dev.crashhunter.core.fix.SetInstanceValue
import dev.crashhunter.core.fix.remote.CurseForgeClient
import dev.crashhunter.core.fix.remote.CurseForgeKeys
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.fix.remote.ModrinthClient
import dev.crashhunter.core.fix.remote.Murmur2
import dev.crashhunter.core.fix.remote.PrismMetaClient
import dev.crashhunter.core.fix.remote.RemoteFetcherImpl
import dev.crashhunter.core.fix.remote.RemoteResolverImpl
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.prism.IniFile
import dev.crashhunter.core.prism.InstanceScanner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FixTest {
    private fun tmp() = Files.createTempDirectory("chfix").toFile()

    private fun diag(cause: Cause, findings: List<Finding> = emptyList()) =
        Diagnosis(CrashSource(SourceKind.CRASH_REPORT, null, 0), cause, findings, emptyList(), "", "", emptyList(), null, null)

    private fun env(root: File, mem: Long = 16384) = FixEnvironment(mem, null, listOf(File(root, "javas")))

    @Test
    fun `local fixes are planned, applied and undone`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        TestJars.forgeMod(mods, "alpha-1.0.jar", "alpha", "1.0")
        TestJars.forgeMod(mods, "alpha-2.0.jar", "alpha", "2.0", pkg = "com.example.alpha2")
        TestJars.fabricMod(mods, "fab.jar", "fabby")
        File(mods, "broken.jar").writeText("not a zip")
        TestJars.forgeMod(mods, "old.jar", "oldmod").let { f ->
            TestJars.jar(f, mapOf("META-INF/mods.toml" to """
                modLoader="javafml"
                loaderVersion="[40,)"
                [[mods]]
                modId="oldmod"
                version="1.0"
                [[dependencies.oldmod]]
                    modId="minecraft"
                    mandatory=true
                    versionRange="[1.19,1.19.3)"
            """.trimIndent()))
        }
        val inst = InstanceScanner.read(dir)
        val index = ModIndex.load(mods)
        val planner = FixPlanner(FixKind.entries.filter { it.defaultOn }.toSet(), env(root))
        val plan = planner.plan(inst, index, emptyList())
        val kinds = plan.associateBy { it.kind }
        assertEquals(listOf(DisableMod("alpha-1.0.jar")), kinds[FixKind.DUPLICATE]?.actions)
        assertEquals(listOf(DisableMod("fab.jar")), kinds[FixKind.WRONG_LOADER]?.actions)
        assertEquals(listOf(DisableMod("broken.jar")), kinds[FixKind.CORRUPT_JAR]?.actions)
        assertEquals(listOf(DisableMod("old.jar")), kinds[FixKind.MC_MISMATCH]?.actions)

        val applier = FixApplier(fixesDir = File(root, "fixes"))
        val rec = applier.apply(inst, plan)
        assertTrue(File(mods, "alpha-1.0.jar.disabled").isFile)
        assertTrue(File(mods, "fab.jar.disabled").isFile)
        assertTrue(File(mods, "alpha-2.0.jar").isFile)
        assertTrue(planner.plan(inst, ModIndex.load(mods), emptyList()).isEmpty())
        val undone = applier.undo(rec)
        assertEquals(FixState.UNDONE, undone.state)
        assertTrue(File(mods, "alpha-1.0.jar").isFile)
        assertTrue(File(mods, "fab.jar").isFile)
        assertEquals(1, applier.history().size)
    }

    @Test
    fun `fixes interrupted while verifying are undone on next start`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        TestJars.fabricMod(mods, "fab.jar", "fabby")
        val inst = InstanceScanner.read(dir)
        val applier = FixApplier(null, File(root, "fixes"))
        val plan = FixPlanner(setOf(FixKind.WRONG_LOADER), env(root)).plan(inst, ModIndex.load(mods), emptyList())
        val rec = applier.apply(inst, plan)
        applier.mark(rec, FixState.VERIFYING)
        assertTrue(File(mods, "fab.jar.disabled").isFile)
        val recovered = FixApplier(null, File(root, "fixes")).recoverInterrupted()
        assertEquals(1, recovered.size)
        assertTrue(File(mods, "fab.jar").isFile)
        assertEquals(FixState.UNDONE, FixApplier(null, File(root, "fixes")).history().single().state)
        assertTrue(FixApplier(null, File(root, "fixes")).recoverInterrupted().isEmpty())
    }

    @Test
    fun `disabled kinds are not proposed and the culprit fix is opt in`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        TestJars.fabricMod(mods, "fab.jar", "fabby")
        TestJars.forgeMod(mods, "boom.jar", "boom")
        val inst = InstanceScanner.read(dir)
        assertTrue(FixPlanner(emptySet(), env(root)).plan(inst, ModIndex.load(mods), emptyList(), listOf("boom.jar")).isEmpty())
        val p = FixPlanner(setOf(FixKind.CULPRIT), env(root)).plan(inst, ModIndex.load(mods), emptyList(), listOf("boom.jar"))
        assertEquals(listOf(DisableMod("boom.jar")), p.single().actions)
    }

    @Test
    fun `memory and java fixes edit instance cfg`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val jdk = File(root, "javas/jdk-17").apply { mkdirs() }
        File(jdk, "release").writeText("JAVA_VERSION=\"17.0.2\"\n")
        File(jdk, "bin").mkdirs(); File(jdk, "bin/java").writeText("")
        val inst = InstanceScanner.read(dir)
        val index = ModIndex.load(inst.modsDir)
        val plan = FixPlanner(setOf(FixKind.MEMORY, FixKind.JAVA), env(root)).plan(inst, index,
            listOf(diag(Cause.OUT_OF_MEMORY), diag(Cause.JAVA_VERSION, listOf(Finding(Cause.JAVA_VERSION, required = "17")))))
        val mem = plan.first { it.kind == FixKind.MEMORY }
        assertTrue(SetInstanceValue("MaxMemAlloc", "6144") in mem.actions)
        val java = plan.first { it.kind == FixKind.JAVA }
        assertTrue(java.actions.any { it is SetInstanceValue && it.key == "JavaPath" && it.value.endsWith("jdk-17/bin/java") })
        FixApplier(fixesDir = File(root, "fixes")).apply(inst, plan)
        val cfg = IniFile.read(File(dir, "instance.cfg"))
        assertEquals("6144", cfg["MaxMemAlloc"])
        assertEquals("true", cfg["OverrideJavaLocation"])
        assertEquals("inst name", cfg["name"])
        assertTrue(FixPlanner(setOf(FixKind.MEMORY), env(root, mem = 4096)).plan(inst, index, listOf(diag(Cause.OUT_OF_MEMORY))).none { it.kind == FixKind.MEMORY && (it.params["to"]?.toInt() ?: 0) <= 6144 })
    }

    @Test
    fun `component version edit keeps other components`() {
        val text = """{"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.minecraftforge","version":"47.1.0","cachedVersion":"47.1.0"}],"formatVersion":1}"""
        val out = FixApplier.withComponentVersion(text, "net.minecraftforge", "47.4.6")
        assertTrue(out.contains("\"47.4.6\"") && !out.contains("cachedVersion") && out.contains("1.20.1") && out.contains("formatVersion"))
    }

    @Test
    fun `curseforge fingerprint ignores whitespace`() {
        val root = tmp()
        val a = File(root, "a").apply { writeText("a b\n\tc\r") }
        val b = File(root, "b").apply { writeText("abc") }
        assertEquals(Murmur2.curseForgeFingerprint(a), Murmur2.curseForgeFingerprint(b))
        assertTrue(Murmur2.curseForgeFingerprint(b) > 0)
    }

    @Test
    fun `curseforge falls back to the user key when the app key is rate limited`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String>()
        server.createContext("/v1/mods/1") { ex ->
            val key = ex.requestHeaders.getFirst("x-api-key") ?: ""
            seen += key
            val (code, body) = if (key == "app") 429 to "{}" else 200 to """{"data":{"id":1,"name":"Lib","allowModDistribution":true}}"""
            ex.sendResponseHeaders(code, body.length.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val keys = CurseForgeKeys({ "app" }, { "user" })
            val cf = CurseForgeClient(Http("test"), keys, "http://127.0.0.1:${server.address.port}/v1")
            assertEquals("Lib", cf.mod(1)?.name)
            assertEquals("user", keys.lastUsed)
            cf.mod(1)
            assertEquals(listOf("app", "user", "user"), seen)
            val noUser = CurseForgeKeys({ null }, { null })
            assertFalse(noUser.available())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `missing dependency is downloaded from modrinth and verified`() = runBlocking {
        val root = tmp()
        val dir = TestJars.instance(root, "inst")
        val mods = File(dir, "minecraft/mods")
        TestJars.forgeMod(mods, "needer.jar", "needer", deps = TestJars.dep("needer", "neededlib", "[2.0,)"))
        val good = TestJars.forgeMod(File(root, "srv"), "neededlib-2.0.jar", "neededlib", "2.0")
        val old = TestJars.forgeMod(File(root, "srv"), "neededlib-1.0.jar", "neededlib", "1.0", pkg = "com.example.nl1")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val host = "http://127.0.0.1:${server.address.port}"
            val bytes: ByteArray? = when {
                path == "/v2/project/neededlib" -> """{"id":"P1","slug":"neededlib","project_type":"mod"}""".toByteArray()
                path == "/v2/project/P1/version" -> ("""[{"id":"V1","project_id":"P1","version_number":"1.0","version_type":"release","date_published":"2026-02-01","files":[{"url":"$host/f/neededlib-1.0.jar","filename":"neededlib-1.0.jar","primary":true,"hashes":{"sha1":"${Http.hash(old, "SHA-1")}"}}]},""" +
                    """{"id":"V2","project_id":"P1","version_number":"2.0","version_type":"release","date_published":"2026-01-01","files":[{"url":"$host/f/neededlib-2.0.jar","filename":"neededlib-2.0.jar","primary":true,"hashes":{"sha1":"${Http.hash(good, "SHA-1")}"}}]}]""").toByteArray()
                path.startsWith("/f/") -> File(root, "srv/" + path.removePrefix("/f/")).readBytes()
                else -> null
            }
            if (bytes == null) { ex.sendResponseHeaders(404, -1); ex.close() }
            else { ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        }
        server.start()
        try {
            val http = Http("test")
            val host = "http://127.0.0.1:${server.address.port}"
            val resolver = RemoteResolverImpl(ModrinthClient(http, "$host/v2"), null, null, PrismMetaClient(http, "$host/meta"))
            val inst = InstanceScanner.read(dir)
            val plan = FixPlanner(setOf(FixKind.ADD_DEPENDENCY), env(root), resolver).plan(inst, ModIndex.load(mods), emptyList())
            val add = plan.single()
            assertEquals(FixKind.ADD_DEPENDENCY, add.kind)
            assertEquals(2, (add.actions.single() as AddMod).candidates.size)
            val applier = FixApplier(RemoteFetcherImpl(http, null, null, File(root, "dl")), File(root, "fixes"))
            val rec = applier.apply(inst, plan)
            assertTrue(File(mods, "neededlib-2.0.jar").isFile)
            assertFalse(File(mods, "neededlib-1.0.jar").exists())
            assertNotNull(rec.steps.firstOrNull())
            applier.undo(rec)
            assertFalse(File(mods, "neededlib-2.0.jar").exists())
        } finally {
            server.stop(0)
        }
    }
}

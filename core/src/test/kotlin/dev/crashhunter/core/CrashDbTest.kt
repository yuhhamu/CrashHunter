package dev.crashhunter.core

import com.sun.net.httpserver.HttpServer
import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.db.ConflictItem
import dev.crashhunter.core.db.CrashDb
import dev.crashhunter.core.db.DbEnv
import dev.crashhunter.core.db.DbRefs
import dev.crashhunter.core.db.DbReport
import dev.crashhunter.core.db.DbSnapshot
import dev.crashhunter.core.db.ModRef
import dev.crashhunter.core.db.SnapBroken
import dev.crashhunter.core.db.SnapConflict
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.model.Loader
import dev.crashhunter.core.mods.ModIndex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashDbTest {
    private fun tmp() = Files.createTempDirectory("chdb").toFile()
    private val profile = GameProfile("1.20.1", Loader.FORGE, "47.4.29")

    @Test
    fun `reports contain no paths and fingerprints are stable`() {
        val fp = CrashFingerprint("org.spongepowered.asm.mixin.throwables.MixinApplyError", listOf("a.B.c", "d.E.f"), "C:\\Users\\someuser\\x", listOf("emextras"))
        val h = DbRefs.fingerprint(fp)!!
        assertEquals(h, DbRefs.fingerprint(fp.copy(message = "/home/other/path")))
        val r = DbReport(install = "id", app = "1.1.0", time = 1, env = DbEnv("1.20.1", "forge", "47.4.29"),
            items = listOf(ConflictItem(listOf(ModRef("a", "1.0", "aa"), ModRef("b", "2.0", "bb")), h, DbRefs.exceptionName(fp), "bisect", 7)))
        val db = CrashDb(tmp(), null, { "" }, { null })
        val text = db.encode(r)
        assertFalse(text.contains("someuser") || text.contains("Users"))
        assertTrue(text.contains("\"type\":\"conflict\""))
        assertEquals(r, db.decode(text))
    }

    @Test
    fun `snapshot knowledge matches by hash or by mod id and version`() {
        val root = tmp()
        val mods = File(root, "mods")
        val a = TestJars.forgeMod(mods, "a-1.0.jar", "moda", "1.0")
        TestJars.forgeMod(mods, "b-2.0.jar", "modb", "2.0")
        TestJars.forgeMod(mods, "c-3.0.jar", "modc", "3.0")
        val snap = DbSnapshot(mc = "1.20.1", loader = "forge",
            conflicts = listOf(SnapConflict(listOf(ModRef("moda", "9.9", DbRefs.sha1(a)), ModRef("modb", "2.0")), n = 3, w = 3.0),
                SnapConflict(listOf(ModRef("modb", "2.0"), ModRef("modz", "1.0")), n = 1, w = 1.0)),
            broken = listOf(SnapBroken(ModRef("modc", "3.0"), "NO_REFMAP", n = 2, w = 1.0), SnapBroken(ModRef("modc", "4.0"), "NO_REFMAP")))
        val k = CrashDb.knowledge(snap, ModIndex.load(mods))
        assertEquals(listOf(Cause.KNOWN_BROKEN, Cause.KNOWN_CONFLICT), k.map { it.cause })
        assertEquals("c-3.0.jar", k[0].jar)
        assertEquals("a-1.0.jar", k[1].jar)
        assertEquals(setOf("c-3.0.jar", "a-1.0.jar"), CrashDb.suspects(snap, ModIndex.load(mods)).toSet())
        val single = snap.copy(broken = listOf(SnapBroken(ModRef("modc", "3.0"), "NO_REFMAP", n = 1, w = 1.0)), conflicts = emptyList())
        assertTrue(CrashDb.knowledge(single, ModIndex.load(mods)).isEmpty())
        assertEquals(listOf("c-3.0.jar"), CrashDb.suspects(single, ModIndex.load(mods)))
        val confirmed = single.copy(broken = listOf(SnapBroken(ModRef("modc", "3.0"), "NO_REFMAP", n = 1, w = 1.0, confirmed = true)))
        assertEquals(listOf(Cause.KNOWN_BROKEN), CrashDb.knowledge(confirmed, ModIndex.load(mods)).map { it.cause })
    }

    @Test
    fun `outbox is sent with the token, bad reports are dropped and snapshots are cached`() = runBlocking {
        val got = CopyOnWriteArrayList<String>()
        var fail = false
        val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        fun signed(data: String, tamper: Boolean = false): String {
            val sig = java.security.Signature.getInstance("Ed25519").run { initSign(kp.private); update(data.toByteArray()); sign() }
            val d = if (tamper) data.replace("42", "43") else data
            return kotlinx.serialization.json.Json.encodeToString(dev.crashhunter.core.db.SignedSnapshot.serializer(), dev.crashhunter.core.db.SignedSnapshot(d, java.util.Base64.getEncoder().encodeToString(sig)))
        }
        val good = """{"schema":1,"mc":"1.20.1","loader":"forge","generated":42,"broken":[{"mod":{"id":"x","v":"1"},"reason":"NO_REFMAP","n":1}]}"""
        var served = signed(good)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val tok = ex.requestHeaders.getFirst("x-ch-token")
            val path = ex.requestURI.path
            val (code, body) = when {
                tok != "t0k" -> 401 to ""
                path == "/v1/reports" && fail -> 503 to ""
                path == "/v1/reports" -> { val b = ex.requestBody.readBytes().toString(Charsets.UTF_8); if (b.contains("\"bad\"")) 400 to "" else { got += b; 200 to "{}" } }
                path == "/v1/snapshot" -> 200 to served
                else -> 404 to ""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
        try {
            val dir = tmp()
            val db = CrashDb(dir, Http("test"), { "http://127.0.0.1:${server.address.port}" }, { "t0k" }, listOf(pub))
            val r = DbReport(install = "id", app = "1", time = 1, env = DbEnv("1.20.1", "forge"), items = listOf(ConflictItem(listOf(ModRef("a")), method = "bisect")))
            db.enqueue(r)
            db.enqueue(r.copy(install = "bad"))
            fail = true
            assertEquals(0, db.flush())
            assertEquals(2, db.pending().size)
            fail = false
            assertEquals(1, db.flush())
            assertEquals(0, db.pending().size)
            assertEquals(1, got.size)
            val s = db.refreshSnapshot(profile)
            assertNotNull(s)
            assertEquals(42, s.generated)
            assertEquals(42, db.snapshot(profile)?.generated)
            served = signed(good, tamper = true)
            assertEquals(42, db.refreshSnapshot(profile, maxAgeMs = 0)?.generated)
            served = signed(good.replace("1.20.1", "1.19.2").replace("42", "99"))
            assertEquals(42, db.refreshSnapshot(profile, maxAgeMs = 0)?.generated)
            served = signed(good.replace("42", "7"))
            assertEquals(42, db.refreshSnapshot(profile, maxAgeMs = 0)?.generated)
            served = good.replace("42", "99")
            assertEquals(42, db.refreshSnapshot(profile, maxAgeMs = 0)?.generated)
            val other = CrashDb(dir, null, { "" }, { null }, listOf(java.util.Base64.getEncoder().encodeToString(java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)))
            assertEquals(null, other.snapshot(profile))
            server.stop(0)
            assertEquals(42, db.refreshSnapshot(profile, maxAgeMs = 0)?.generated)
        } finally {
            server.stop(0)
        }
    }
}

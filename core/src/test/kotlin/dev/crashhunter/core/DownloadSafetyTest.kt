package dev.crashhunter.core

import com.sun.net.httpserver.HttpServer
import dev.crashhunter.core.fix.FetchRules
import dev.crashhunter.core.fix.RemoteFile
import dev.crashhunter.core.fix.RemoteSource
import dev.crashhunter.core.fix.SafeNames
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.fix.remote.RemoteFetcherImpl
import dev.crashhunter.core.prism.InstanceScanner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSafetyTest {
    @Test
    fun `file names from a download source cannot leave the mods folder`() {
        val dir = Files.createTempDirectory("chmods").toFile()
        assertEquals("a.jar", SafeNames.jar("../../a.jar"))
        assertEquals("b.jar", SafeNames.jar("..\\..\\b.jar"))
        assertEquals(File(dir, "c.jar").canonicalFile, SafeNames.inside(dir, "x/../c.jar").canonicalFile)
        assertFailsWith<IllegalArgumentException> { SafeNames.jar("evil.exe") }
        assertFailsWith<IllegalArgumentException> { SafeNames.jar("..") }
        assertFailsWith<IllegalArgumentException> { SafeNames.jar(".hidden.jar") }
        assertFailsWith<IllegalArgumentException> { SafeNames.jar("a:b.jar") }
    }

    @Test
    fun `only https is allowed except for this computer`() {
        assertTrue(Http.secure("https://cdn.modrinth.com/a.jar"))
        assertTrue(Http.secure("http://127.0.0.1:8080/a.jar"))
        assertFalse(Http.secure("http://example.com/a.jar"))
        assertFalse(Http.secure("file:///C:/a.jar"))
    }

    @Test
    fun `downloads over the size limit are stopped`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/big") { ex -> ex.sendResponseHeaders(200, 0); ex.responseBody.use { o -> repeat(40) { o.write(ByteArray(1024)) } } }
        server.start()
        try {
            val dest = File(Files.createTempDirectory("chdl").toFile(), "big.jar")
            assertFailsWith<java.io.IOException> { Http("test").download("http://127.0.0.1:${server.address.port}/big", dest, maxBytes = 10 * 1024) }
            assertFalse(dest.exists())
            assertFalse(File(dest.path + ".part").exists())
        } finally { server.stop(0) }
    }

    @Test
    fun `candidates without a checksum are not used unless allowed`() = runBlocking {
        val root = Files.createTempDirectory("chhash").toFile()
        val inst = InstanceScanner.read(TestJars.instance(root, "inst"))
        val c = RemoteFile(RemoteSource.MODRINTH, "lib.jar", "http://127.0.0.1:1/lib.jar", null, null, "p", "f", "1")
        val e = assertFailsWith<Exception> { RemoteFetcherImpl(Http("test"), null, null, File(root, "dl")).fetch(listOf(c), null, null, inst, FetchRules()) }
        assertTrue(e.message!!.contains("no hash"), e.message)
    }
}

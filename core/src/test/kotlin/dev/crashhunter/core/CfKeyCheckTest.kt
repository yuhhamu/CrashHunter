package dev.crashhunter.core

import com.sun.net.httpserver.HttpServer
import dev.crashhunter.core.fix.remote.CurseForgeClient
import dev.crashhunter.core.fix.remote.CurseForgeKeys
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.fix.remote.KeyCheck
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class CfKeyCheckTest {
    @Test
    fun `key check tells working, rejected and wrongly formatted keys apart`() = runBlocking {
        val good = "\$2a\$10\$" + "a".repeat(53)
        val revoked = "\$2a\$10\$" + "b".repeat(53)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val ok = ex.requestHeaders.getFirst("x-api-key") == good
            val body = if (ok) """{"data":{"id":432}}""".toByteArray() else ByteArray(0)
            ex.sendResponseHeaders(if (ok) 200 else 403, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        server.start()
        try {
            val cf = CurseForgeClient(Http("test"), CurseForgeKeys({ null }, { null }), "http://127.0.0.1:${server.address.port}/v1")
            assertEquals(KeyCheck.OK, cf.checkKey(good))
            assertEquals(KeyCheck.REJECTED, cf.checkKey(revoked))
            assertEquals(KeyCheck.WRONG_FORMAT, cf.checkKey("123e4567-e89b-12d3-a456-426614174000"))
            assertEquals(KeyCheck.EMPTY, cf.checkKey(" "))
        } finally {
            server.stop(0)
        }
    }
}

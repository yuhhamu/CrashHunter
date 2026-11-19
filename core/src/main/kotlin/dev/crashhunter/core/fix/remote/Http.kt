package dev.crashhunter.core.fix.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

class HttpStatusException(val status: Int, val url: String, body: String) : RuntimeException("HTTP $status $url ${body.take(200)}")

class Http(
    val userAgent: String,
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build(),
) {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = send(build(url, headers).GET().build())

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        send(build(url, headers + ("Content-Type" to "application/json")).POST(HttpRequest.BodyPublishers.ofString(body)).build())

    suspend fun download(url: String, dest: File, headers: Map<String, String> = emptyMap(), maxBytes: Long = MAX_DOWNLOAD): File = withContext(Dispatchers.IO) {
        require(secure(url)) { "insecure url: $url" }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val res = client.send(build(url, headers).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        try {
            if (res.statusCode() !in 200..299) throw HttpStatusException(res.statusCode(), url, "")
            require(secure(res.uri().toString())) { "insecure redirect: ${res.uri()}" }
            val declared = res.headers().firstValueAsLong("Content-Length").orElse(-1)
            if (declared > maxBytes) throw java.io.IOException("too large: $declared bytes")
            res.body().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw java.io.IOException("too large: over $maxBytes bytes")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Throwable) {
            runCatching { res.body().close() }
            tmp.delete()
            throw e
        }
        java.nio.file.Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        dest
    }

    private fun build(url: String, headers: Map<String, String>): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", userAgent).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    private suspend fun send(req: HttpRequest): String = withContext(Dispatchers.IO) {
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) throw HttpStatusException(res.statusCode(), req.uri().toString(), res.body() ?: "")
        res.body()
    }

    companion object {
        const val MAX_DOWNLOAD = 256L * 1024 * 1024

        fun secure(url: String): Boolean {
            val u = runCatching { URI.create(url) }.getOrNull() ?: return false
            if (u.scheme.equals("https", true)) return true
            val host = u.host?.lowercase() ?: return false
            return u.scheme.equals("http", true) && (host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1")
        }

        fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

        fun hash(file: File, algo: String): String {
            val md = MessageDigest.getInstance(algo)
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun userAgent(version: String) = "yuhhamu/CrashHunter/$version (https://github.com/yuhhamu/CrashHunter)"
    }
}

object Murmur2 {
    fun curseForgeFingerprint(file: File): Long {
        val raw = file.readBytes()
        val bytes = ByteArray(raw.size)
        var n = 0
        for (b in raw) if (b != 9.toByte() && b != 10.toByte() && b != 13.toByte() && b != 32.toByte()) bytes[n++] = b
        return hash(bytes, n, 1).toLong() and 0xffffffffL
    }

    fun hash(data: ByteArray, length: Int, seed: Int): Int {
        val m = 0x5bd1e995
        val r = 24
        var h = seed xor length
        var i = 0
        while (length - i >= 4) {
            var k = (data[i].toInt() and 0xff) or ((data[i + 1].toInt() and 0xff) shl 8) or ((data[i + 2].toInt() and 0xff) shl 16) or ((data[i + 3].toInt() and 0xff) shl 24)
            k *= m
            k = k xor (k ushr r)
            k *= m
            h *= m
            h = h xor k
            i += 4
        }
        when (length - i) {
            3 -> { h = h xor ((data[i + 2].toInt() and 0xff) shl 16); h = h xor ((data[i + 1].toInt() and 0xff) shl 8); h = h xor (data[i].toInt() and 0xff); h *= m }
            2 -> { h = h xor ((data[i + 1].toInt() and 0xff) shl 8); h = h xor (data[i].toInt() and 0xff); h *= m }
            1 -> { h = h xor (data[i].toInt() and 0xff); h *= m }
        }
        h = h xor (h ushr 13)
        h *= m
        h = h xor (h ushr 15)
        return h
    }
}

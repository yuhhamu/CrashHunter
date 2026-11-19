package dev.crashhunter.app

import dev.crashhunter.core.util.AppDirs
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Updater {
    const val PUBLIC_KEY = "MCowBQYDK2VwAyEAqbphWCLzOjftvUptjktkQJ+8iQ9v+067jNB3nAbTLBk="
    private val OCTET = mapOf("Accept" to "application/octet-stream")

    fun installed(): Boolean {
        val exe = System.getProperty("jpackage.app-path")?.let(::File) ?: return false
        return File(exe.parentFile, "unins000.exe").isFile
    }

    fun verifySignature(data: ByteArray, signature: String, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        Signature.getInstance("Ed25519").run { initVerify(key); update(data); verify(Base64.getDecoder().decode(signature.trim())) }
    }.getOrDefault(false)

    fun expectedHash(sums: String, fileName: String): String? =
        sums.lineSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
            Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").find(line)?.takeIf { it.groupValues[2].trim() == fileName }?.groupValues?.get(1)?.lowercase()
        }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun download(ctx: AppContext, r: UpdateCheck.Release): File {
        val name = "CrashHunter-Setup-${r.version}.exe"
        val setupUrl = r.assets[name] ?: error("no installer in the release")
        val sumsUrl = r.assets["SHA256SUMS.txt"] ?: error("no SHA256SUMS.txt")
        val sigUrl = r.assets["SHA256SUMS.txt.sig"] ?: error("no signature")
        val dir = File(AppDirs.dataDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val sums = ctx.http.download(sumsUrl, File(dir, "SHA256SUMS.txt"), OCTET)
        val sig = ctx.http.download(sigUrl, File(dir, "SHA256SUMS.txt.sig"), OCTET)
        if (!verifySignature(sums.readBytes(), sig.readText())) error("signature mismatch")
        val expected = expectedHash(sums.readText(), name) ?: error("installer not listed")
        val setup = ctx.http.download(setupUrl, File(dir, name), OCTET)
        if (sha256(setup) != expected) { setup.delete(); error("hash mismatch") }
        return setup
    }

    val SETUP_ARGS = listOf("/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RELAUNCH=1")

    fun commandLine(setup: File): String {
        val log = File(setup.parentFile, "setup.log").absolutePath
        return (listOf("\"${setup.absolutePath}\"") + SETUP_ARGS + "/LOG=\"$log\"").joinToString(" ")
    }

    fun detachScript(setup: File): String {
        fun q(v: String) = "'" + v.replace("'", "''") + "'"
        return "\$ProgressPreference = 'SilentlyContinue'; \$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = ${q(commandLine(setup))}; CurrentDirectory = ${q(setup.parentFile.absolutePath)} }; [Console]::Out.Write([string]\$r.ReturnValue)"
    }

    fun install(setup: File) {
        when (detached(setup)) {
            false -> ProcessBuilder(listOf(setup.absolutePath) + SETUP_ARGS).directory(setup.parentFile).start()
            else -> Unit
        }
    }

    fun parseReturnValue(output: String): Int? = Regex("(-?\\d+)\\s*$").find(output.trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun detached(setup: File): Boolean? {
        val encoded = Base64.getEncoder().encodeToString(detachScript(setup).toByteArray(Charsets.UTF_16LE))
        val p = runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return false
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        return when (val code = parseReturnValue(out)) {
            null -> null
            else -> code == 0
        }
    }
}

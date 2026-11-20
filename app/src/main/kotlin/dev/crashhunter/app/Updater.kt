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
    const val BACKUP_KEY = "MCowBQYDK2VwAyEAmTaxUCUY7QDd6XdcPrPFyPUCiZNx5WtIWFY5Cp3Keqg="
    val KEYS: List<String> get() = listOf(PUBLIC_KEY, BACKUP_KEY).filter { it.startsWith("MC") }
    const val MANIFEST = "manifest.json"

    data class Manifest(val app: String, val version: String, val published: String, val files: Map<String, String>, val revoke: List<String>)

    private fun revokedFile() = File(AppDirs.dataDir, "revoked-keys.txt")

    fun revoked(file: File = revokedFile()): Set<String> = runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())

    fun parseManifest(text: String): Manifest? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val files = (o["files"] as kotlinx.serialization.json.JsonObject).mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content.lowercase() }
        val revoke = when (val r = o["revoke"]) {
            is kotlinx.serialization.json.JsonArray -> r.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            is kotlinx.serialization.json.JsonPrimitive -> listOfNotNull(r.content.takeIf { r.isString && it.isNotBlank() })
            else -> emptyList()
        }
        Manifest(str("app")!!, str("version")!!, str("published") ?: "", files, revoke)
    }.getOrNull()

    fun checkManifest(data: ByteArray, signature: String, releaseVersion: String, current: String,
                      keys: List<String> = KEYS, revokedFile: File = revokedFile()): Manifest {
        val trusted = keys.filter { it !in revoked(revokedFile) }
        val signer = trusted.firstOrNull { verifySignature(data, signature, it) } ?: error("signature mismatch")
        val m = parseManifest(data.toString(Charsets.UTF_8)) ?: error("bad manifest")
        if (m.app != "CrashHunter") error("bad manifest")
        if (m.version != releaseVersion) error("version mismatch")
        if (!UpdateCheck.isNewer(m.version, current)) error("not newer")
        if (signer in m.revoke) error("signer revoked")
        val add = m.revoke.filter { it in keys && it !in revoked(revokedFile) }
        if (add.isNotEmpty()) runCatching { revokedFile.parentFile?.mkdirs(); revokedFile.appendText(add.joinToString("\n", postfix = "\n")) }
        return m
    }
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
        val manifestUrl = r.assets[MANIFEST] ?: error("no manifest")
        val sigUrl = r.assets["$MANIFEST.sig"] ?: error("no signature")
        val dir = File(AppDirs.dataDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val manifest = ctx.http.download(manifestUrl, File(dir, MANIFEST), OCTET, maxBytes = 1L shl 20)
        val sig = ctx.http.download(sigUrl, File(dir, "$MANIFEST.sig"), OCTET, maxBytes = 1L shl 16)
        val m = checkManifest(manifest.readBytes(), sig.readText(), r.version, VERSION)
        val expected = m.files[name] ?: error("installer not listed")
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

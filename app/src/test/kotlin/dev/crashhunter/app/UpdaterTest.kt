package dev.crashhunter.app

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    @Test
    fun verifiesSignedSums() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(pair.public.encoded)
        val data = "abc  CrashHunter-Setup-1.2.2.exe\r\n".toByteArray()
        val sig = Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(pair.private); update(data); sign() })
        assertTrue(Updater.verifySignature(data, sig, pub))
        assertFalse(Updater.verifySignature(data + 1, sig, pub))
        assertFalse(Updater.verifySignature(data, sig))
        assertFalse(Updater.verifySignature(data, "not base64", pub))
    }

    @Test
    fun findsTheInstallerHash() {
        val h = "a".repeat(64)
        val sums = "${"b".repeat(64)}  CrashHunter-1.2.2-portable.zip\r\n$h  CrashHunter-Setup-1.2.2.exe\r\n"
        assertEquals(h, Updater.expectedHash(sums, "CrashHunter-Setup-1.2.2.exe"))
        assertNull(Updater.expectedHash(sums, "CrashHunter-Setup-1.2.3.exe"))
    }

    @Test
    fun readsReleaseAssets() {
        val r = UpdateCheck.parse("""{"tag_name":"v1.2.2","html_url":"https://github.com/yuhhamu/CrashHunter/releases/tag/v1.2.2","assets":[{"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/yuhhamu/CrashHunter/releases/download/v1.2.2/SHA256SUMS.txt"},{"name":"evil.exe","browser_download_url":"https://example.com/evil.exe"}]}""")!!
        assertEquals("1.2.2", r.version)
        assertEquals(setOf("SHA256SUMS.txt"), r.assets.keys)
    }

    @Test
    fun buildsTheDetachedCommand() {
        val setup = java.io.File("C:/Users/o'neil/AppData/Roaming/CrashHunter/updates/CrashHunter-Setup-1.2.4.exe")
        val cl = Updater.commandLine(setup)
        assertTrue(cl.startsWith("\"${setup.absolutePath}\" /SILENT"))
        assertTrue(cl.contains("/RELAUNCH=1"))
        assertTrue(cl.endsWith("setup.log\""))
        val script = Updater.detachScript(setup)
        assertTrue(script.contains("o''neil"))
        assertFalse(script.contains("o'neil"))
    }

    @Test
    fun readsTheReturnValue() {
        assertEquals(0, Updater.parseReturnValue("0"))
        assertEquals(0, Updater.parseReturnValue("#< CLIXML\r\n0\r\n"))
        assertEquals(9, Updater.parseReturnValue("9"))
        assertNull(Updater.parseReturnValue(""))
        assertTrue(Updater.detachScript(java.io.File("C:/x/CrashHunter-Setup-1.2.6.exe")).startsWith("\$ProgressPreference = 'SilentlyContinue';"))
    }

    private fun keyPair() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun pub(p: java.security.KeyPair) = Base64.getEncoder().encodeToString(p.public.encoded)
    private fun sign(p: java.security.KeyPair, data: ByteArray) = Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(p.private); update(data); sign() })
    private fun manifest(v: String, revoke: List<String> = emptyList()) =
        """{"app":"CrashHunter","version":"$v","published":"2026-11-20T00:00:00Z","files":{"CrashHunter-Setup-$v.exe":"${"A".repeat(64)}"},"revoke":[${revoke.joinToString(",") { "\"$it\"" }}]}""".toByteArray()

    @Test
    fun acceptsASignedNewerManifest() {
        val main = keyPair(); val backup = keyPair()
        val rev = java.io.File(java.nio.file.Files.createTempDirectory("chrev").toFile(), "revoked.txt")
        val data = manifest("0.1.1")
        val m = Updater.checkManifest(data, sign(main, data), "0.1.1", "0.1.0", listOf(pub(main), pub(backup)), rev)
        assertEquals("a".repeat(64), m.files["CrashHunter-Setup-0.1.1.exe"])
        val byBackup = Updater.checkManifest(data, sign(backup, data), "0.1.1", "0.1.0", listOf(pub(main), pub(backup)), rev)
        assertEquals("0.1.1", byBackup.version)
    }

    @Test
    fun refusesOlderOrMismatchedVersions() {
        val main = keyPair()
        val rev = java.io.File(java.nio.file.Files.createTempDirectory("chrev").toFile(), "revoked.txt")
        val old = manifest("0.1.0")
        assertTrue(runCatching { Updater.checkManifest(old, sign(main, old), "0.1.0", "0.1.0", listOf(pub(main)), rev) }.isFailure)
        val newer = manifest("0.1.2")
        assertTrue(runCatching { Updater.checkManifest(newer, sign(main, newer), "0.1.3", "0.1.0", listOf(pub(main)), rev) }.isFailure)
        assertTrue(runCatching { Updater.checkManifest(newer, sign(keyPair(), newer), "0.1.2", "0.1.0", listOf(pub(main)), rev) }.isFailure)
    }

    @Test
    fun theBackupKeyCanRevokeTheMainKey() {
        val main = keyPair(); val backup = keyPair()
        val keys = listOf(pub(main), pub(backup))
        val rev = java.io.File(java.nio.file.Files.createTempDirectory("chrev").toFile(), "revoked.txt")
        val rotation = manifest("0.2.0", listOf(pub(main)))
        Updater.checkManifest(rotation, sign(backup, rotation), "0.2.0", "0.1.0", keys, rev)
        assertTrue(pub(main) in Updater.revoked(rev))
        val forged = manifest("0.3.0")
        assertTrue(runCatching { Updater.checkManifest(forged, sign(main, forged), "0.3.0", "0.2.0", keys, rev) }.isFailure)
        val selfRevoke = manifest("0.2.1", listOf(pub(backup)))
        assertTrue(runCatching { Updater.checkManifest(selfRevoke, sign(backup, selfRevoke), "0.2.1", "0.2.0", keys, rev) }.isFailure)
    }
}

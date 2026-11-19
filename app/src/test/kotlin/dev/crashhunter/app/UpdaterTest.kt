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
}

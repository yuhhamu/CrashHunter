package dev.crashhunter.core.verify

import dev.crashhunter.core.db.ModRef
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticVerifierTest {
    private fun jar(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("verify", ".jar")
        ZipOutputStream(f.outputStream()).use { z -> for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return f
    }

    private fun check(reason: String, f: File) = runBlocking { StaticVerifier.verify(PendingFile(ModRef("m", "1"), reason), f).verdict }

    @Test
    fun judgesRefmaps() {
        val cfg = """{"package":"a","mixins":["X"],"refmap":"m.refmap.json"}"""
        assertEquals(Verdict.CONFIRMED, check("NO_REFMAP", jar("m.mixins.json" to cfg)))
        assertEquals(Verdict.REJECTED, check("NO_REFMAP", jar("m.mixins.json" to cfg, "m.refmap.json" to "{}")))
        assertEquals(Verdict.INCONCLUSIVE, check("NO_REFMAP", jar("m.mixins.json" to """{"package":"a","mixins":["X"]}""")))
        assertEquals(Verdict.REJECTED, check("NO_REFMAP", jar("m.mixins.json" to """{"package":"a","mixins":[]}""")))
    }

    @Test
    fun judgesCorruptJars() {
        assertEquals(Verdict.REJECTED, check("BROKEN", jar("a.txt" to "x")))
        val bad = jar("a.txt" to "x".repeat(1000))
        val bytes = bad.readBytes()
        bytes[40] = (bytes[40] + 1).toByte()
        bad.writeBytes(bytes)
        assertEquals(Verdict.CONFIRMED, check("BROKEN", bad))
        assertEquals(Verdict.INCONCLUSIVE, check("CRASH", bad))
    }
}

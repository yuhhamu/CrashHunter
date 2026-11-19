package dev.crashhunter.core

import dev.crashhunter.core.analyze.LaunchCrashDetector
import dev.crashhunter.core.bisect.LogTail
import dev.crashhunter.core.bisect.MenuMarkers
import dev.crashhunter.core.prism.IniFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LargeLogTest {
    private fun big(f: File, tailLine: String) {
        f.bufferedWriter().use { w ->
            val line = "[20Nov2026 03:18:42.195] [main/INFO] [spam/]: " + "x".repeat(150) + "\n"
            repeat(240_000) { w.write(line) }
            w.write(tailLine + "\n")
        }
    }

    @Test
    fun `the title screen is found at the end of a very large log`() {
        val f = File(Files.createTempDirectory("chlog").toFile(), "latest.log")
        big(f, "[20Nov2026 03:21:07.064] [Render thread/WARN] [ModernFix/]: Game took 149.005 seconds to start")
        assertTrue(f.length() > 30_000_000)
        val tail = LogTail(f)
        val markers = MenuMarkers.load()
        var found: Int? = null
        repeat(4) { tail.next(0)?.let { t -> markers.settleFor(t)?.let { found = it } } }
        assertNotNull(found)
        assertEquals(markers.strongSettleSec, found)
    }

    @Test
    fun `a crash written at the end of a very large log is detected`() {
        val dir = Files.createTempDirectory("chgame").toFile()
        File(dir, "logs").mkdirs()
        big(File(dir, "logs/latest.log"), "[20Nov2026 03:21:07.064] [main/FATAL] [mod/]: Mod loading has failed")
        assertNotNull(LaunchCrashDetector.detect(dir, 0))
    }

    @Test
    fun `the tail starts over when the log is replaced`() {
        val f = File(Files.createTempDirectory("chlog2").toFile(), "latest.log")
        f.writeText("one\ntwo\n")
        val tail = LogTail(f)
        assertEquals("one\ntwo\n", tail.next(0))
        assertNull(tail.next(0))
        f.writeText("x\n")
        assertEquals("x\n", tail.next(0))
    }

    @Test
    fun `instance settings are written into the general section`() {
        val text = "[General]\nname=a\nOverrideConsole=false\n\n[UI]\nfoo=1\n"
        val out = IniFile.withValue(IniFile.withValue(text, "ShowConsoleOnError", "false"), "OverrideConsole", "true")
        val lines = out.lines()
        assertTrue(lines.indexOf("ShowConsoleOnError=false") in 1 until lines.indexOf("[UI]"), out)
        assertTrue("OverrideConsole=true" in lines && "OverrideConsole=false" !in lines, out)
    }
}

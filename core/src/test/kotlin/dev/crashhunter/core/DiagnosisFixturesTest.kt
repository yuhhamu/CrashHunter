package dev.crashhunter.core

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.analyze.SolutionKb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosisFixturesTest {
    private val service = DiagnosisService(SolutionKb.load("ja"))

    private fun fixture(name: String): Diagnosis {
        val text = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return service.diagnoseText(text)
    }

    @Test
    fun `every fixture yields a diagnosis`() {
        val dir = java.io.File(javaClass.getResource("/fixtures")!!.toURI())
        for (f in dir.listFiles()!!.sortedBy { it.name }) {
            val d = service.diagnoseText(f.readText())
            println("${f.name}: ${d.cause} | ${d.topSuspect?.name} (${d.topSuspect?.score}) | ${d.detail}")
            d.solutions.forEach { println("    - $it") }
            assertTrue(d.title.isNotBlank(), f.name)
        }
    }

    @Test
    fun `missing dependency names the requiring mod and the dependency`() {
        val d = fixture("crash-2026-08-20_21.28.49-fml.txt")
        assertEquals(Cause.MISSING_DEPENDENCY, d.cause, d.detail)
        assertEquals("functionalstorage", d.topSuspect?.modId)
        assertTrue(d.detail.contains("titanium"), d.detail)
    }

    @Test
    fun `neoforge loader too old reads the current version`() {
        val d = fixture("crash-2026-08-25_01.02.13-fml.txt")
        assertEquals(Cause.LOADER_VERSION, d.cause)
        assertTrue(d.detail.contains("21.1.169"), d.detail)
        assertTrue(d.detail.contains("21.1.238"), d.detail)
    }

    @Test
    fun `class mismatch inside a mod failure block`() {
        val d = fixture("crash-2026-11-07_16.45.21-fml.txt")
        assertEquals(Cause.CLASS_MISMATCH, d.cause)
        assertEquals("mekanismgenerators", d.topSuspect?.modId)
    }

    @Test
    fun `tick crash points at the mod in the top frame`() {
        val d = fixture("crash-2026-11-09_01.44.14-server.txt")
        assertEquals(Cause.MOD_RUNTIME_ERROR, d.cause)
        assertEquals("fourfoldfactory", d.topSuspect?.modId)
    }

    @Test
    fun `legacy duplicate mods`() {
        val d = fixture("crash-2026-11-09_18.38.31-server.txt")
        assertEquals(Cause.DUPLICATE_MOD, d.cause)
    }

    @Test
    fun `glfw failure is a graphics problem`() {
        assertEquals(Cause.GPU_DRIVER, fixture("crash-2026-08-28_06.14.00-client.txt").cause)
    }

    @Test
    fun `hs_err is a native crash`() {
        val d = fixture("hs_err_pid577.log")
        assertEquals(Cause.NATIVE_CRASH, d.cause)
    }

    @Test
    fun `watchdog is a hang`() {
        assertEquals(Cause.WATCHDOG_HANG, fixture("crash-2026-09-10_19.38.54-server.txt").cause)
    }
}

package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.BisectEngine
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.TrialEvent
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.TrialRunner
import dev.crashhunter.core.launcher.WindowArranger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressSlotsTest {
    private val fp = CrashFingerprint("java.lang.RuntimeException", listOf("a.B"), "boom", listOf("x"))

    @Test
    fun `events carry the slot that ran the trial and the current pool size`() = runBlocking {
        val all = (1..40).map { "mod$it.jar" }
        val events = Collections.synchronizedList(mutableListOf<TrialEvent>())
        val runners = (0 until 2).map { slot ->
            TrialRunner { enabled ->
                delay(10)
                if ("mod23.jar" in enabled) TrialOutcome(TrialResult.FAIL, fp, note = "slot$slot") else TrialOutcome(TrialResult.PASS, note = "slot$slot")
            }
        }
        val engine = BisectEngine(all, runners[0], target = fp, extraRunners = runners.drop(1), listener = { events += it })
        val r = withContext(Dispatchers.Default) { engine.run() }
        assertEquals(BisectStatus.FOUND, r.status)
        val done = events.filter { it.outcome != null }
        assertEquals(setOf(0, 1), done.map { it.slot }.toSet())
        done.forEach { e -> assertEquals("slot${e.slot}", e.outcome!!.note) }
        val starts = events.filter { it.outcome == null }.associateBy { it.number }
        done.forEach { e -> assertEquals(starts[e.number]!!.slot, e.slot) }
        val pools = events.filter { it.phase == "search" }.map { it.pool }
        assertEquals(40, pools.first())
        assertTrue(pools.last() < 40)
    }

    @Test
    fun `window script sets the title prefix and minimizes only when asked`() {
        val a = WindowArranger.script(listOf(11, 22), "CrashHunter #2 - ", true)
        assertTrue("@(11,22)" in a)
        assertTrue("'CrashHunter #2 - '" in a)
        assertTrue("ShowWindowAsync(\$p.MainWindowHandle, 7)" in a)
        val b = WindowArranger.script(listOf(5), "x'; Remove-Item C:\\ -Recurse; '", false)
        assertFalse("ShowWindowAsync(\$p.MainWindowHandle" in b)
        assertFalse("'; Remove-Item" in b)
        val c = WindowArranger.script(listOf(5), null, true)
        assertFalse("SetWindowText(\$p" in c)
    }
}

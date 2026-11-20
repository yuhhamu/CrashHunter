package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.fix.RemoteFile
import dev.crashhunter.core.fix.RemoteSource
import dev.crashhunter.core.tune.EngineState
import dev.crashhunter.core.tune.Removed
import dev.crashhunter.core.tune.ReplaceDone
import dev.crashhunter.core.tune.Replaced
import dev.crashhunter.core.tune.TuneEngine
import dev.crashhunter.core.tune.TuneEvent
import dev.crashhunter.core.tune.TuneFind
import dev.crashhunter.core.tune.TuneStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuneLoopTest {
    private fun remote(n: String) = RemoteFile(RemoteSource.MODRINTH, n, null, null, null, "1", n, n)

    private class World(vararg mods: String) {
        val enabled = mods.toMutableSet()
        fun broken() = "a" in enabled && "b" in enabled
    }

    private fun engine(w: World, fp: CrashFingerprint, finds: () -> List<String>, versions: (String) -> List<RemoteFile> = { emptyList() },
                       events: MutableList<TuneEvent> = mutableListOf(), resume: EngineState? = null, saved: MutableList<EngineState> = mutableListOf()) = TuneEngine(
        initialPolicy = emptyMap(),
        find = { _ -> if (w.broken()) TuneFind.Culprits(finds().filter { it in w.enabled }, fp) else TuneFind.Launches },
        versions = versions,
        replace = { f, r -> w.enabled -= f; w.enabled += r.fileName; ReplaceDone(r.fileName) { w.enabled -= r.fileName; w.enabled += f } },
        launch = { if (w.broken()) TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS) },
        dependents = { setOf(it) },
        disable = { w.enabled -= it },
        sameFailure = { x, y -> x == y },
        removeFirst = true,
        enable = { w.enabled += it },
        listener = { events += it },
        resume = resume,
        checkpoint = { saved += it },
    )

    @Test
    fun `a removal that did not help is put back and another candidate is removed`() = runBlocking {
        val w = World("x", "a", "b")
        val events = mutableListOf<TuneEvent>()
        val r = engine(w, CrashFingerprint("boom"), { listOf("x", "a", "b") }, events = events).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertTrue("x" in w.enabled, w.enabled.toString())
        assertEquals(listOf("a"), r.steps.filterIsInstance<Removed>().map { it.file })
        assertTrue(events.any { it is TuneEvent.Ineffective && it.file == "x" })
    }

    @Test
    fun `a saved run from before also puts back a removal that did not help`() = runBlocking {
        val w = World("a", "b", "y")
        w.enabled -= "x"
        val old = Removed(7, "x", emptyList(), listOf("x", "a", "b"))
        val state = EngineState(8, listOf(old), listOf(dev.crashhunter.core.tune.PendingState(old, listOf("x"))))
        val r = engine(w, CrashFingerprint("boom"), { listOf("y", "a", "b") }, resume = state).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertTrue("x" in w.enabled, w.enabled.toString())
        assertTrue(r.steps.none { it is Removed && it.file == "x" })
    }

    @Test
    fun `a version mismatch tries other versions before removing anything`() = runBlocking {
        val w = World("a", "b")
        val r = engine(w, CrashFingerprint("cause:CLASS_MISMATCH"), { listOf("a", "b") }, versions = { if (it == "b") listOf(remote("b2")) else emptyList() }).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals("b2", r.steps.filterIsInstance<Replaced>().single().newFile)
        assertTrue(r.steps.none { it is Removed })
    }
}

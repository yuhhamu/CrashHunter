package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.PRISM_UNRESPONSIVE
import dev.crashhunter.core.fix.RemoteFile
import dev.crashhunter.core.fix.RemoteSource
import dev.crashhunter.core.tune.EngineState
import dev.crashhunter.core.tune.Removed
import dev.crashhunter.core.tune.ReplaceDone
import dev.crashhunter.core.tune.Replaced
import dev.crashhunter.core.tune.TuneCheckpoint
import dev.crashhunter.core.tune.TuneEngine
import dev.crashhunter.core.tune.TuneFind
import dev.crashhunter.core.tune.TuneResume
import dev.crashhunter.core.tune.TuneStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuneResumeTest {
    private val fp = CrashFingerprint("boom")
    private fun remote(n: String) = RemoteFile(RemoteSource.MODRINTH, n, null, null, null, "1", n, n)

    private class World {
        val enabled = mutableSetOf("a", "b", "c", "d")
        var prismDown = false
        fun crash() = when {
            "a" in enabled && "b" in enabled -> listOf("a", "b")
            "c" in enabled && "d" in enabled -> listOf("c", "d")
            else -> null
        }
    }

    private fun engine(w: World, saved: MutableList<EngineState>, resume: EngineState? = null) = TuneEngine(
        initialPolicy = emptyMap(),
        find = { _ -> w.crash()?.let { TuneFind.Culprits(it, fp) } ?: TuneFind.Launches },
        versions = { f -> if (f == "c") listOf(remote("c2")) else emptyList() },
        replace = { f, r -> w.enabled -= f; w.enabled += r.fileName; ReplaceDone(r.fileName) { w.enabled -= r.fileName; w.enabled += f } },
        launch = { if (w.prismDown) TrialOutcome(TrialResult.START_FAILED, note = PRISM_UNRESPONSIVE) else if (w.crash() == null) TrialOutcome(TrialResult.PASS) else TrialOutcome(TrialResult.FAIL, fp) },
        dependents = { setOf(it) },
        disable = { w.enabled -= it },
        sameFailure = { a, b -> a == b },
        removeFirst = true,
        enable = { w.enabled += it },
        resume = resume,
        checkpoint = { saved += it },
    )

    @Test
    fun `a prism failure stops the run and the saved state continues it`() = runBlocking {
        val w = World()
        val saved = mutableListOf<EngineState>()
        w.prismDown = false
        val first = engine(w, saved)
        var calls = 0
        val e = TuneEngine(
            initialPolicy = emptyMap(),
            find = { _ -> calls++; if (calls == 2) w.prismDown = true; w.crash()?.let { TuneFind.Culprits(it, fp) } ?: TuneFind.Launches },
            versions = { f -> if (f == "c") listOf(remote("c2")) else emptyList() },
            replace = { f, r -> w.enabled -= f; w.enabled += r.fileName; ReplaceDone(r.fileName) { w.enabled -= r.fileName; w.enabled += f } },
            launch = { if (w.prismDown) TrialOutcome(TrialResult.START_FAILED, note = PRISM_UNRESPONSIVE) else if (w.crash() == null) TrialOutcome(TrialResult.PASS) else TrialOutcome(TrialResult.FAIL, fp) },
            dependents = { setOf(it) },
            disable = { w.enabled -= it },
            sameFailure = { a, b -> a == b },
            removeFirst = true,
            enable = { w.enabled += it },
            checkpoint = { saved += it },
        )
        first.hashCode()
        val r = e.run()
        assertEquals(TuneStatus.START_FAILED, r.status)
        assertEquals(PRISM_UNRESPONSIVE, r.message)
        assertEquals(setOf("b", "d"), w.enabled)
        val last = saved.last()
        assertEquals(3, last.round)
        assertEquals(listOf("a", "c"), last.steps.filterIsInstance<Removed>().map { it.file })
        assertEquals(listOf("c"), last.pending.map { it.removed.file })

        w.prismDown = false
        val r2 = engine(w, saved, resume = last).run()
        assertEquals(TuneStatus.LAUNCHED, r2.status)
        assertEquals("c2", r2.steps.filterIsInstance<Replaced>().single().newFile)
        assertTrue(r2.steps.any { it is Removed && it.file == "a" })
        assertEquals(setOf("b", "d", "c2"), w.enabled)
    }

    @Test
    fun `checkpoints are saved, found again and cleared`() {
        val dir = Files.createTempDirectory("tr").toFile()
        val copy = File(dir, "inst-tune-CrashHunter").apply { mkdirs(); File(this, dev.crashhunter.core.bisect.WorkCopy.MARKER).writeText("x") }
        val store = TuneResume(File(dir, "resume"))
        val st = EngineState(3, listOf(Removed(1, "a.jar", emptyList(), listOf("a.jar", "b.jar")),
            dev.crashhunter.core.tune.Fixed(2, dev.crashhunter.core.fix.FixKind.DUPLICATE, "x", mapOf("k" to "v"), listOf(dev.crashhunter.core.fix.DisableMod("x.jar")), false)))
        store.save(TuneCheckpoint("inst", copy.absolutePath, 1, st, listOf(listOf("x.jar")), staticDone = true))
        store.mark("inst", TuneStatus.START_FAILED, PRISM_UNRESPONSIVE)
        val c = assertNotNull(store.load("inst"))
        assertEquals(3, c.engine.round)
        assertEquals("START_FAILED", c.status)
        assertEquals("a.jar", (c.engine.steps.first() as Removed).file)
        assertEquals(st, c.engine)
        assertNull(store.load("other"))
        copy.deleteRecursively()
        assertNull(store.load("inst"))
        store.clear("inst")
    }
}

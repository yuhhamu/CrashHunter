package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.BisectEngine
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.TrialRunner
import dev.crashhunter.core.bisect.UNDECIDED
import dev.crashhunter.core.bisect.Verdict
import dev.crashhunter.core.tune.Suspicion
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchJudgeTest {
    private val fp = CrashFingerprint("java.lang.IncompatibleClassChangeError", listOf("ars.Jelly"), "overrides final method", listOf("arsdelight"))
    private val other = CrashFingerprint("java.lang.IllegalStateException", listOf("q.Q"), "other", listOf("q"))
    private val mods = (1..60).map { "mod$it" }

    @Test
    fun `hangs and other crashes are not counted as passes`() = runBlocking {
        val culprits = setOf("mod50", "mod51")
        val noisy = setOf("mod20", "mod35")
        val rnd = Random(7)
        val r = BisectEngine(mods, TrialRunner { e ->
            when {
                e.containsAll(culprits) -> TrialOutcome(TrialResult.FAIL, fp)
                e.containsAll(noisy) && rnd.nextInt(3) == 0 -> TrialOutcome(TrialResult.HANG)
                "mod20" in e && rnd.nextInt(3) == 0 -> TrialOutcome(TrialResult.FAIL, other)
                else -> TrialOutcome(TrialResult.PASS)
            }
        }, target = fp).run()
        assertEquals(BisectStatus.FOUND, r.status)
        assertEquals(culprits, r.culprits.toSet())
    }

    @Test
    fun `a hang target treats crashes as unknown and finds the hang`() = runBlocking {
        val r = BisectEngine(mods, TrialRunner { e ->
            when {
                "mod44" in e && "mod12" in e -> TrialOutcome(TrialResult.HANG)
                "mod30" in e -> TrialOutcome(TrialResult.FAIL, other)
                else -> TrialOutcome(TrialResult.PASS)
            }
        }, hangIsFailure = true).run()
        assertEquals(BisectStatus.FOUND, r.status)
        assertEquals(setOf("mod12", "mod44"), r.culprits.toSet())
    }

    @Test
    fun `too many unknown trials end undecided instead of guessing`() = runBlocking {
        val r = BisectEngine(mods, TrialRunner { e ->
            when {
                "mod59" in e -> TrialOutcome(TrialResult.FAIL, fp)
                e.size > 1 -> TrialOutcome(TrialResult.HANG)
                else -> TrialOutcome(TrialResult.PASS)
            }
        }, target = fp, maxUnknown = 3).run()
        assertTrue(r.status == BisectStatus.FOUND && r.culprits == listOf("mod59") || r.status == BisectStatus.INCONCLUSIVE && r.message == UNDECIDED, "$r")
    }

    @Test
    fun `verdicts reach the suspicion board`() = runBlocking {
        val board = Suspicion()
        val seen = mutableListOf<Verdict>()
        BisectEngine(mods, TrialRunner { e -> if ("mod7" in e) TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS) }, target = fp,
            onVerdict = { set, v -> seen += v; board.trial(set, mods.size, v) }).run()
        assertTrue(Verdict.REPRO in seen && Verdict.PASS in seen)
        assertEquals("mod7", board.rank(mods).first())
    }

    @Test
    fun `suspicion orders static and database hits first and passes push down`() {
        val board = Suspicion()
        board.add("b", 3.0, "static:FINAL_OVERRIDE")
        board.fromDb(listOf("c"), emptyList(), emptyList(), emptyList())
        board.trial(setOf("c"), 10, Verdict.PASS)
        assertEquals(listOf("b", "c", "a"), board.rank(listOf("a", "c", "b")))
        assertTrue(board.of("c") < board.of("b"))
    }

    @Test
    fun `a second deterministic failure is moved aside and the first one is still found`() = runBlocking {
        val a = CrashFingerprint("org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException", listOf("x.Main"), "ModifyArg", emptyList())
        val b = CrashFingerprint("cause:CLASS_MISMATCH", listOf("ars.ADItems"), "", listOf("arsdelight"))
        val all = (1..120).map { "m$it" }
        val moved = mutableListOf<String>()
        var trials = 0
        val r = BisectEngine(all, TrialRunner { e ->
            trials++
            when {
                "m40" in e && "m110" in e -> TrialOutcome(TrialResult.FAIL, a)
                "m3" in e && "m5" in e -> TrialOutcome(TrialResult.FAIL, b)
                "m70" in e -> TrialOutcome(TrialResult.HANG)
                else -> TrialOutcome(TrialResult.PASS)
            }
        }, target = a, suspects = listOf("m3", "m5"), demote = { o -> if (o.fingerprint == b) listOf("m3", "m5") else emptyList() }, onDemote = { moved += it }).run()
        assertEquals(BisectStatus.FOUND, r.status, r.toString())
        assertEquals(setOf("m40", "m110"), r.culprits.toSet())
        assertEquals(listOf("m3", "m5"), moved)
        assertTrue(trials < 45, "trials=$trials")
    }
}

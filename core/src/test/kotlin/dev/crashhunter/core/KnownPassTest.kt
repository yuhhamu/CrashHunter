package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.BisectEngine
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.TrialRunner
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnownPassTest {
    private val fp1 = CrashFingerprint("java.lang.RuntimeException", listOf("a.B"), "one", listOf("x"))
    private val fp2 = CrashFingerprint("java.lang.IllegalStateException", listOf("c.D"), "two", listOf("y"))

    @Test
    fun `a later search skips launches that are subsets of earlier successful ones`() = runBlocking {
        val mods = (1..120).map { "mod$it" }
        val known = mutableListOf<Set<String>>()
        val first = AtomicInteger()
        val r1 = BisectEngine(mods, TrialRunner { e ->
            first.incrementAndGet()
            if ("mod100" in e) TrialOutcome(TrialResult.FAIL, fp1)
            else if ("mod40" in e && "mod90" in e) TrialOutcome(TrialResult.FAIL, fp2)
            else TrialOutcome(TrialResult.PASS)
        }, target = fp1, knownPass = known).run()
        assertEquals(BisectStatus.FOUND, r1.status)
        assertEquals(listOf("mod100"), r1.culprits)
        assertTrue(known.isNotEmpty())

        val rest = mods - "mod100"
        val cold = AtomicInteger()
        val warm = AtomicInteger()
        fun runner(c: AtomicInteger) = TrialRunner { e ->
            c.incrementAndGet()
            if ("mod40" in e && "mod90" in e) TrialOutcome(TrialResult.FAIL, fp2) else TrialOutcome(TrialResult.PASS)
        }
        val coldResult = BisectEngine(rest, runner(cold), target = fp2).run()
        val engine = BisectEngine(rest, runner(warm), target = fp2, knownPass = known)
        val warmResult = engine.run()
        assertEquals(coldResult.culprits.toSet(), warmResult.culprits.toSet())
        assertEquals(setOf("mod40", "mod90"), warmResult.culprits.toSet())
        assertTrue(warm.get() < cold.get(), "warm=${warm.get()} cold=${cold.get()}")
        assertTrue(engine.inferred > 0)
    }
}

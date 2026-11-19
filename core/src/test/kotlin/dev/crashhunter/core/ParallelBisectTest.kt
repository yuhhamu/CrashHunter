package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.BisectEngine
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.TrialRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelBisectTest {
    private val fp = CrashFingerprint("java.lang.RuntimeException", listOf("a.B"), "boom", listOf("x"))
    private fun mods(n: Int) = (1..n).map { "mod$it.jar" }

    private class Pool(val culprits: Set<String>, val size: Int, val fp: CrashFingerprint) {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val total = AtomicInteger()
        val rounds = AtomicInteger()
        fun runners(): List<TrialRunner> = (0 until size).map { _ ->
            TrialRunner { enabled ->
                val now = running.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                total.incrementAndGet()
                delay(30)
                running.decrementAndGet()
                if (enabled.containsAll(culprits)) TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS)
            }
        }
    }

    private suspend fun timed(engine: BisectEngine): Pair<dev.crashhunter.core.bisect.BisectResult, Long> {
        val t0 = System.nanoTime()
        val r = withContext(Dispatchers.Default) { engine.run() }
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `parallel runners find the same culprits as one runner, in fewer rounds`() = runBlocking {
        val all = mods(60)
        for (culprits in listOf(setOf("mod17.jar"), setOf("mod3.jar", "mod44.jar"), setOf("mod60.jar"))) {
            val one = Pool(culprits, 1, fp)
            val (r1, t1) = timed(BisectEngine(all, one.runners()[0], target = fp))
            val three = Pool(culprits, 3, fp)
            val rs = three.runners()
            val (r3, t3) = timed(BisectEngine(all, rs[0], target = fp, extraRunners = rs.drop(1)))
            assertEquals(BisectStatus.FOUND, r1.status)
            assertEquals(BisectStatus.FOUND, r3.status, "$culprits")
            assertEquals(culprits, r3.culprits.toSet())
            assertEquals(r1.culprits.toSet(), r3.culprits.toSet())
            assertTrue(three.peak.get() > 1, "ran concurrently")
            assertTrue(t3 < t1, "parallel $t3 ms should be faster than serial $t1 ms for $culprits")
        }
    }

    @Test
    fun `parallel baseline handles no failure and not a mod`() = runBlocking {
        val p = Pool(emptySet(), 2, fp)
        val rs = p.runners()
        val r = BisectEngine(mods(10), { TrialOutcome(TrialResult.PASS) }, target = fp, extraRunners = listOf(TrialRunner { TrialOutcome(TrialResult.PASS) })).run()
        assertEquals(BisectStatus.NO_FAILURE_AT_BASELINE, r.status)
        val r2 = BisectEngine(mods(10), { TrialOutcome(TrialResult.FAIL, fp) }, target = fp, extraRunners = listOf(TrialRunner { TrialOutcome(TrialResult.FAIL, fp) })).run()
        assertEquals(BisectStatus.NOT_A_MOD, r2.status)
    }

    @Test
    fun `parallel count follows free memory and limits`() {
        assertEquals(1, dev.crashhunter.core.bisect.Parallelism.choose(0, 3, 6144, 6000))
        assertEquals(2, dev.crashhunter.core.bisect.Parallelism.choose(0, 3, 4096, 11000))
        assertEquals(3, dev.crashhunter.core.bisect.Parallelism.choose(0, 3, 2048, 64000))
        assertEquals(2, dev.crashhunter.core.bisect.Parallelism.choose(2, 3, 8192, 1000))
        assertEquals(3, dev.crashhunter.core.bisect.Parallelism.choose(9, 3, 8192, 1000))
    }
}

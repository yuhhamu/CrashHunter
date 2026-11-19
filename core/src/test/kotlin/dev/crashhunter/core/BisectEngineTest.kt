package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.BisectEngine
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BisectEngineTest {
    private val fp = CrashFingerprint("java.lang.RuntimeException", listOf("a.B"), "boom", listOf("x"))
    private val other = CrashFingerprint("java.lang.IllegalStateException", listOf("q.Q"), "other", listOf("q"))

    private fun mods(n: Int) = (1..n).map { "mod$it.jar" }

    private fun runner(culprits: Set<String>, requires: Map<String, Set<String>> = emptyMap(), counter: IntArray = IntArray(1)) =
        dev.crashhunter.core.bisect.TrialRunner { enabled ->
            counter[0]++
            for (m in enabled) if (requires[m]?.any { it !in enabled } == true) return@TrialRunner TrialOutcome(TrialResult.START_FAILED, note = "missing dep of $m")
            if (enabled.containsAll(culprits)) TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS)
        }

    private fun closure(requires: Map<String, Set<String>>): (Collection<String>) -> Set<String> = { names ->
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque(names)
        while (stack.isNotEmpty()) { val n = stack.removeLast(); if (out.add(n)) requires[n]?.forEach { stack.addLast(it) } }
        out
    }

    @Test
    fun `finds a single culprit for every position`() = runBlocking {
        val all = mods(37)
        for (c in all) {
            val r = BisectEngine(all, runner(setOf(c)), target = fp).run()
            assertEquals(BisectStatus.FOUND, r.status, c)
            assertEquals(listOf(c), r.culprits)
        }
    }

    @Test
    fun `finds a pair that only crashes together`() = runBlocking {
        val rnd = Random(4)
        repeat(30) {
            val all = mods(rnd.nextInt(4, 60))
            val pair = all.shuffled(rnd).take(2).toSet()
            val r = BisectEngine(all, runner(pair), target = fp).run()
            assertEquals(BisectStatus.FOUND, r.status)
            assertEquals(pair, r.culprits.toSet())
        }
    }

    @Test
    fun `never breaks dependencies and reports them as requirements`() = runBlocking {
        val all = mods(20)
        val requires = mapOf("mod5.jar" to setOf("mod1.jar"), "mod9.jar" to setOf("mod5.jar"), "mod12.jar" to setOf("mod1.jar"))
        val r = BisectEngine(all, runner(setOf("mod9.jar"), requires), closure(requires), target = fp).run()
        assertEquals(BisectStatus.FOUND, r.status)
        assertEquals(listOf("mod9.jar"), r.culprits)
        assertEquals(setOf("mod5.jar", "mod1.jar"), r.requirements.toSet())
    }

    @Test
    fun `suspect first saves trials`() = runBlocking {
        val all = mods(200)
        val withSuspect = IntArray(1)
        val without = IntArray(1)
        BisectEngine(all, runner(setOf("mod150.jar"), counter = withSuspect), suspects = listOf("mod150.jar"), target = fp).run()
        BisectEngine(all, runner(setOf("mod150.jar"), counter = without), target = fp).run()
        assertTrue(withSuspect[0] < without[0], "${withSuspect[0]} vs ${without[0]}")
        assertTrue(without[0] <= 22, "trials ${without[0]}")
    }

    @Test
    fun `no crash at baseline`() = runBlocking {
        val r = BisectEngine(mods(5), { TrialOutcome(TrialResult.PASS) }, target = fp).run()
        assertEquals(BisectStatus.NO_FAILURE_AT_BASELINE, r.status)
    }

    @Test
    fun `crash without mods is not a mod problem`() = runBlocking {
        val r = BisectEngine(mods(5), { TrialOutcome(TrialResult.FAIL, fp) }, target = fp).run()
        assertEquals(BisectStatus.NOT_A_MOD, r.status)
    }

    @Test
    fun `a different crash does not count`() = runBlocking {
        val all = mods(16)
        val r = BisectEngine(all, { enabled ->
            when {
                "mod13.jar" in enabled -> TrialOutcome(TrialResult.FAIL, fp)
                "mod2.jar" in enabled -> TrialOutcome(TrialResult.FAIL, other)
                else -> TrialOutcome(TrialResult.PASS)
            }
        }, target = fp).run()
        assertEquals(listOf("mod13.jar"), r.culprits)
        assertTrue(r.otherCrashSeen)
    }

    @Test
    fun `cached results resume without new launches`() = runBlocking {
        val all = mods(30)
        val first = BisectEngine(all, runner(setOf("mod7.jar")), target = fp)
        first.run()
        val counter = IntArray(1)
        val r = BisectEngine(all, runner(setOf("mod7.jar"), counter = counter), target = fp, cache = LinkedHashMap(first.cache)).run()
        assertEquals(listOf("mod7.jar"), r.culprits)
        assertEquals(0, counter[0])
    }
}

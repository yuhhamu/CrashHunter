package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.BisectEngine
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.TrialRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefixSearchTest {
    private val fp = CrashFingerprint("java.lang.RuntimeException", listOf("a.B"), "boom", listOf("x"))
    private val testB = listOf("chpaira", "chpairb", "chneedslib", "chlib", "chfill1", "chfill2", "chfill3", "chfill4", "chfill5", "chfill6")

    private fun runners(n: Int, culprits: Set<String>, count: AtomicInteger) = (0 until n).map {
        TrialRunner { enabled ->
            count.incrementAndGet()
            if (enabled.containsAll(culprits)) TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS)
        }
    }

    private fun run(mods: List<String>, culprits: Set<String>, parallel: Int, suspects: List<String> = emptyList(),
                    deps: Map<String, String> = emptyMap()): Pair<List<String>, Int> = runBlocking {
        val count = AtomicInteger()
        val rs = runners(parallel, culprits, count)
        val engine = BisectEngine(mods, rs[0], suspects = suspects, target = fp, extraRunners = rs.drop(1),
            withRequirements = { set -> set.toSet() + set.mapNotNull { deps[it] } })
        val r = withContext(Dispatchers.Default) { engine.run() }
        assertEquals(BisectStatus.FOUND, r.status)
        r.culprits to count.get()
    }

    @Test
    fun `test-b pair is found in far fewer trials than before`() {
        val (c1, n1) = run(testB, setOf("chpaira", "chpairb"), 1, suspects = listOf("chpaira"))
        assertEquals(setOf("chpaira", "chpairb"), c1.toSet())
        println("testB p1=$n1")
        assertTrue(n1 <= 12, "sequential trials $n1")
        val (c2, n2) = run(testB, setOf("chpaira", "chpairb"), 2, suspects = listOf("chpaira"))
        assertEquals(setOf("chpaira", "chpairb"), c2.toSet())
        println("testB p2=$n2")
        assertTrue(n2 <= 16, "parallel trials $n2")
    }

    @Test
    fun `single, pair and triple culprits in a large pack`() {
        val mods = (1..150).map { "mod$it" }
        for (culprits in listOf(setOf("mod77"), setOf("mod3", "mod140"), setOf("mod10", "mod50", "mod120"))) {
            for (p in listOf(1, 2, 3)) {
                val (found, n) = run(mods, culprits, p)
                assertEquals(culprits, found.toSet())
                val limit = 2 + culprits.size * (p * 9 + p) + 2
                assertTrue(n <= limit, "culprits=$culprits p=$p trials=$n")
            }
        }
    }

    @Test
    fun `a culprit that needs a library is reported without the library`() {
        val (found, _) = run(testB, setOf("chneedslib", "chlib"), 2, deps = mapOf("chneedslib" to "chlib"))
        assertEquals(listOf("chneedslib"), found)
    }

    @Test
    fun `a broken library is blamed instead of the mod that needs it`() {
        val mods = listOf("astral", "a", "b", "c", "lib", "d", "e")
        val (found, _) = run(mods, setOf("lib"), 2, deps = mapOf("astral" to "lib"))
        assertEquals(listOf("lib"), found)
        val (found1, _) = run(mods, setOf("lib"), 1, suspects = listOf("astral"), deps = mapOf("astral" to "lib"))
        assertEquals(listOf("lib"), found1)
    }
}

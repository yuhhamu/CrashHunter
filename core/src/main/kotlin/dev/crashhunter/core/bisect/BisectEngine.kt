package dev.crashhunter.core.bisect

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.analyze.StackTraceTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

@Serializable
enum class TrialResult { PASS, FAIL, HANG, START_FAILED, CANCELLED }

const val PRISM_UNRESPONSIVE = "prismUnresponsive"
const val STALLED = "stalled"
const val INFERRED = "inferred"
const val UNDECIDED = "undecided"

@Serializable
data class TrialOutcome(
    val result: TrialResult,
    val fingerprint: CrashFingerprint? = null,
    val note: String = "",
    val crashFile: String? = null,
)

fun interface TrialRunner {
    suspend fun run(enabled: Set<String>): TrialOutcome
}

enum class Verdict { REPRO, PASS, UNKNOWN }

enum class BisectStatus { FOUND, NO_FAILURE_AT_BASELINE, NOT_A_MOD, START_FAILED, INCONCLUSIVE, CANCELLED, ERROR }

data class BisectResult(
    val status: BisectStatus,
    val culprits: List<String> = emptyList(),
    val requirements: List<String> = emptyList(),
    val trials: Int = 0,
    val message: String = "",
    val otherCrashSeen: Boolean = false,
)

data class TrialEvent(val number: Int, val estimate: Int, val enabledCount: Int, val outcome: TrialOutcome?, val phase: String, val enabled: Set<String> = emptySet(), val slot: Int = 0, val pool: Int = 0, val focus: List<String> = emptyList())

class BisectEngine(
    private val candidates: List<String>,
    private val runner: TrialRunner,
    private val withRequirements: (Collection<String>) -> Set<String> = { it.toSet() },
    private val suspects: List<String> = emptyList(),
    private var target: CrashFingerprint? = null,
    private val anyCrash: Boolean = false,
    private val hangIsFailure: Boolean = false,
    private val repeats: Int = 1,
    val cache: MutableMap<String, TrialOutcome> = LinkedHashMap(),
    private val listener: (TrialEvent) -> Unit = {},
    private val extraRunners: List<TrialRunner> = emptyList(),
    val knownPass: MutableList<Set<String>> = mutableListOf(),
    private val onVerdict: (Set<String>, Verdict) -> Unit = { _, _ -> },
    private val maxUnknown: Int = 8,
) {
    @Volatile var unknowns = 0
        private set
    private var targetHang = false
    @Volatile var inferred = 0
        private set

    init {
        for ((k, o) in cache) if (o.result == TrialResult.PASS) rememberPass(if (k.isEmpty()) emptySet() else k.split('\u0000').toSet())
    }
    private val runners = listOf(runner) + extraRunners
    val parallel: Int get() = runners.size
    private var trials = 0
    private var otherCrash = false
    private val candidateSet = candidates.toSet()
    private fun currentEstimate(): Int = if (projected > 0) projected else estimate
    val estimate: Int get() = 2 + runners.size * (roundsFor(candidates.size) + 1)
    @Volatile private var started = 0
    @Volatile var pool: Int = candidates.size
        private set
    @Volatile var focus: List<String> = candidates
        private set

    private fun key(set: Set<String>) = keyOf(set)

    companion object {
        fun keyOf(set: Collection<String>): String = set.toSortedSet().joinToString("\u0000")
    }

    private suspend fun runSet(enabled: Set<String>, phase: String): TrialOutcome = runBatch(listOf(enabled), phase).single()

    private suspend fun runOn(slot: Int, enabled: Set<String>, phase: String): TrialOutcome {
        val r = runners[slot]
        var outcome: TrialOutcome? = null
        for (i in 0 until maxOf(1, repeats)) {
            val n = synchronized(this) { ++started }
            synchronized(this) { listener(TrialEvent(n, maxOf(currentEstimate(), n), enabled.size, null, phase, enabled, slot, pool, focus)) }
            var o = r.run(enabled)
            if (o.result == TrialResult.START_FAILED && o.note == PRISM_UNRESPONSIVE) throw StartFailure(o.note)
            if (o.result == TrialResult.START_FAILED && phase != "baseline" && phase != "without-mods") {
                synchronized(this) { listener(TrialEvent(n, maxOf(currentEstimate(), n), enabled.size, null, "retry", enabled, slot, pool)) }
                o = r.run(enabled)
                if (o.result == TrialResult.START_FAILED) throw StartFailure(o.note)
            }
            val done = synchronized(this) { ++trials }
            synchronized(this) { listener(TrialEvent(n, maxOf(currentEstimate(), done), enabled.size, o, phase, enabled, slot, pool)) }
            if (o.result == TrialResult.CANCELLED) throw CancellationException("cancelled")
            outcome = o
            if (o.result != TrialResult.PASS) break
        }
        return outcome!!
    }

    private suspend fun runBatch(sets: List<Set<String>>, phase: String): List<TrialOutcome> {
        for (set in sets) {
            val k = key(set)
            if (cache[k] == null && passedBefore(set)) {
                cache[k] = TrialOutcome(TrialResult.PASS, note = INFERRED)
                inferred++
            }
        }
        val todo = sets.map { key(it) to it }.filter { (k, _) -> cache[k] == null }.distinctBy { it.first }
        for (chunk in todo.chunked(runners.size)) {
            val outs = if (chunk.size == 1) listOf(runOn(0, chunk[0].second, phase)) else coroutineScope {
                chunk.mapIndexed { i, (_, set) -> async { runOn(i, set, phase) } }.awaitAll()
            }
            chunk.forEachIndexed { i, (k, set) ->
                cache[k] = outs[i]
                if (outs[i].result == TrialResult.PASS) rememberPass(set)
                if (target != null || targetHang || anyCrash) runCatching { onVerdict(set, verdict(outs[i])) }
            }
        }
        return sets.map { cache[key(it)]!! }
    }

    private fun passedBefore(set: Set<String>): Boolean = synchronized(knownPass) { knownPass.any { it.containsAll(set) } }

    private fun rememberPass(set: Set<String>) = synchronized(knownPass) {
        if (knownPass.any { it.containsAll(set) }) return@synchronized
        knownPass.removeAll { set.containsAll(it) }
        knownPass += set
    }

    private fun req(subset: Collection<String>): Set<String> = withRequirements(subset).filter { it in candidateSet }.toSet()

    fun verdict(o: TrialOutcome): Verdict = when (o.result) {
        TrialResult.PASS -> Verdict.PASS
        TrialResult.FAIL -> when {
            anyCrash -> Verdict.REPRO
            targetHang -> { otherCrash = true; Verdict.UNKNOWN }
            else -> {
                val t = target
                val fp = o.fingerprint
                if (t == null || fp == null || StackTraceTools.sameFailure(t, fp)) Verdict.REPRO else { otherCrash = true; Verdict.UNKNOWN }
            }
        }
        TrialResult.HANG -> if (targetHang || hangIsFailure) Verdict.REPRO else Verdict.UNKNOWN
        else -> Verdict.UNKNOWN
    }

    private fun reproduced(o: TrialOutcome): Boolean = verdict(o) == Verdict.REPRO

    private suspend fun confirms(subset: Collection<String>): Boolean {
        repeat(2) {
            val o = runSet(req(subset), "confirm")
            when (verdict(o)) {
                Verdict.REPRO -> return true
                Verdict.PASS -> return false
                Verdict.UNKNOWN -> { unknowns++; cache.remove(key(req(subset))) }
            }
        }
        return false
    }

    private class Undecided(val range: List<String>) : RuntimeException()

    private class StartFailure(val note: String) : RuntimeException(note)

    suspend fun run(): BisectResult = try {
        runInner()
    } catch (e: StartFailure) {
        BisectResult(BisectStatus.START_FAILED, trials = trials, message = e.note)
    } catch (e: CancellationException) {
        BisectResult(BisectStatus.CANCELLED, trials = trials)
    }

    private suspend fun runInner(): BisectResult {
        if (runners.size > 1) runBatch(listOf(candidateSet, emptySet()), "baseline")
        var base = runSet(candidateSet, "baseline")
        var again = 0
        while (again < 2 && (base.result == TrialResult.HANG && !hangIsFailure ||
                base.result == TrialResult.FAIL && !anyCrash && target != null && base.fingerprint != null && !StackTraceTools.sameFailure(target!!, base.fingerprint!!))) {
            again++
            cache.remove(key(candidateSet))
            base = runSet(candidateSet, "baseline")
        }
        when (base.result) {
            TrialResult.PASS -> return BisectResult(BisectStatus.NO_FAILURE_AT_BASELINE, trials = trials)
            TrialResult.START_FAILED -> return BisectResult(BisectStatus.START_FAILED, trials = trials, message = base.note)
            TrialResult.HANG -> if (!hangIsFailure) return BisectResult(BisectStatus.INCONCLUSIVE, trials = trials, message = base.note)
            else -> {}
        }
        targetHang = base.result == TrialResult.HANG
        if (target == null && !targetHang) target = base.fingerprint
        if (!reproduced(base)) return BisectResult(BisectStatus.NO_FAILURE_AT_BASELINE, trials = trials, message = base.note, otherCrashSeen = true)

        val none = runSet(emptySet(), "without-mods")
        if (none.result == TrialResult.START_FAILED) return BisectResult(BisectStatus.START_FAILED, trials = trials, message = none.note)
        if (reproduced(none)) return BisectResult(BisectStatus.NOT_A_MOD, trials = trials)

        val order = dependenciesFirst(suspects.filter { it in candidateSet } + candidates.filter { it !in suspects })
        val minimal = try {
            findCulprits(order, suspects.count { it in candidateSet })
        } catch (e: Undecided) {
            return BisectResult(BisectStatus.INCONCLUSIVE, e.range, trials = trials, otherCrashSeen = otherCrash, message = UNDECIDED)
        }
        pool = minimal.size
        val confirmed = minimal.isNotEmpty() && confirms(minimal)
        if (!confirmed) return BisectResult(BisectStatus.INCONCLUSIVE, minimal, trials = trials, otherCrashSeen = otherCrash,
            message = "flaky")
        val req = withRequirements(minimal).filter { it in candidateSet && it !in minimal }
        return BisectResult(BisectStatus.FOUND, minimal, req, trials, otherCrashSeen = otherCrash)
    }

    private fun dependenciesFirst(list: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        val visiting = HashSet<String>()
        fun visit(x: String) {
            if (x in out || !visiting.add(x)) return
            for (d in req(listOf(x))) if (d != x) visit(d)
            out += x
        }
        list.forEach { visit(it) }
        return out.toList()
    }

    @Volatile private var projected = 0

    private fun roundsFor(range: Int): Int {
        var r = 0
        var n = range
        while (n > 1) { n = (n + runners.size) / (runners.size + 1); r++ }
        return r
    }

    private fun project(range: Int) {
        projected = started + runners.size * (roundsFor(range) + 1)
    }

    private suspend fun findCulprits(order: List<String>, hintCount: Int): List<String> {
        val found = mutableListOf<String>()
        var rest = order
        var lowKnown = 0
        var first = true
        while (rest.isNotEmpty()) {
            val hints = if (first && hintCount > 0) listOf(1, hintCount) else emptyList()
            val k = prefixSearch(rest, found.toSet(), lowKnown, hints)
            first = false
            if (k <= 0) break
            found += rest[k - 1]
            rest = rest.subList(0, k - 1)
            lowKnown = -1
            if (k == 1) break
        }
        return found
    }

    private suspend fun prefixSearch(rest: List<String>, fixed: Set<String>, lowKnown: Int, hints: List<Int>): Int {
        var lo = lowKnown
        var hi = rest.size
        var extra = (if (lo < 0) listOf(0) else emptyList()) + hints.filter { it in (lo + 1) until hi }
        extra = extra.distinct().sorted()
        val unknownAt = HashSet<Int>()
        var misses = 0
        var jumped = -1
        val once = HashSet<Int>()
        while (hi - lo > 1) {
            pool = hi - maxOf(lo, 0)
            focus = fixed.toList() + rest.subList(maxOf(lo, 0), hi)
            project(hi - lo)
            val lowest = unknownAt.filter { it in (lo + 1) until hi }.minOrNull()
            if ((hi - 1) in unknownAt && jumped != hi && lowest == lo + 1) {
                jumped = hi
                if (verdict(runSet(req(fixed + rest.subList(0, maxOf(lo, 0)) + rest[hi - 1]), "search")) == Verdict.REPRO) return hi
            }
            val firstPoints = extra.filter { it !in unknownAt }.take(runners.size)
            extra = emptyList()
            val free = runners.size - firstPoints.size
            val (gapLo, gapHi) = gap(lo, hi, unknownAt)
            val evenLo = if (firstPoints.isEmpty()) gapLo else maxOf(gapLo, firstPoints.max())
            val span = gapHi - evenLo
            val even = if (free <= 0 || span <= 1) emptyList() else (1..free).map { j -> evenLo + (span.toLong() * j / (free + 1)).toInt() }
                .map { it.coerceIn(evenLo + 1, gapHi - 1) }
            val taken = HashSet<Int>()
            val points = (firstPoints + even).mapNotNull { p -> nearestFree(p, lo, hi, unknownAt, taken)?.also { taken += it } }.distinct().sorted()
            if (points.isEmpty()) {
                if (jumped != hi && hi in 1..rest.size && verdict(runSet(req(fixed + rest.subList(0, maxOf(lo, 0)) + rest[hi - 1]), "search")) == Verdict.REPRO) return hi
                throw Undecided(fixed.toList() + rest.subList(maxOf(lo, 0), hi))
            }
            val sets = points.map { req(fixed + rest.subList(0, maxOf(0, it))) }
            val outs = runBatch(sets, "search")
            val verdicts = outs.map { verdict(it) }
            var newHi = hi
            var newLo = lo
            points.forEachIndexed { i, pt -> if (verdicts[i] == Verdict.REPRO && pt < newHi) newHi = pt }
            points.forEachIndexed { i, pt -> if (verdicts[i] == Verdict.PASS && pt < newHi && pt > newLo) newLo = pt }
            points.forEachIndexed { i, pt ->
                if (verdicts[i] != Verdict.UNKNOWN) return@forEachIndexed
                unknowns++
                misses++
                if (once.add(pt)) cache.remove(key(sets[i])) else unknownAt += pt
            }
            if (misses > maxUnknown) throw Undecided(fixed.toList() + rest.subList(maxOf(newLo, 0), newHi))
            hi = newHi
            lo = newLo
        }
        return hi
    }

    private fun gap(lo: Int, hi: Int, unknownAt: Set<Int>): Pair<Int, Int> {
        val inside = unknownAt.filter { it in (lo + 1) until hi }.sorted()
        if (inside.isEmpty()) return lo to hi
        val top = inside.last()
        if (hi - top > 1) return top to hi
        if (inside.first() - lo > 1) return lo to inside.first()
        val bars = listOf(lo) + inside + hi
        return bars.zipWithNext().filter { (a, b) -> b - a > 1 }.maxByOrNull { (a, b) -> b - a } ?: (lo to hi)
    }

    private fun nearestFree(p: Int, lo: Int, hi: Int, bad: Set<Int>, taken: Set<Int>): Int? {
        val min = lo + 1
        val max = hi - 1
        if (min > max) return null
        val start = p.coerceIn(min, max)
        for (d in 0..(max - min)) {
            for (c in listOf(start - d, start + d)) if (c in min..max && c !in bad && c !in taken) return c
        }
        return null
    }

}

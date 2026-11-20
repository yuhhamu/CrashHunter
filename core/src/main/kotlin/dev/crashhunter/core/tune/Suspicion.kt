package dev.crashhunter.core.tune

import dev.crashhunter.core.bisect.Verdict
import dev.crashhunter.core.scan.StaticFinding
import kotlin.math.ln

class Suspicion {
    private val score = HashMap<String, Double>()
    private val why = HashMap<String, MutableSet<String>>()

    fun add(file: String, weight: Double, reason: String) {
        score[file] = (score[file] ?: 0.0) + weight
        why.getOrPut(file) { LinkedHashSet() } += reason
    }

    fun of(file: String): Double = score[file] ?: 0.0

    fun reasons(file: String): Set<String> = why[file].orEmpty()

    fun fromStatic(findings: List<StaticFinding>) {
        for (f in findings) {
            add(f.file, f.kind.weight, "static:${f.kind}")
            f.other?.takeIf { it != f.file }?.let { add(it, f.kind.weight * OTHER_SIDE, "static:${f.kind}") }
        }
    }

    fun fromDb(confirmed: Collection<String>, conflicts: Collection<String>, fixes: Collection<String>, crashes: Collection<String>) {
        confirmed.forEach { add(it, 3.0, "db:broken") }
        conflicts.forEach { add(it, 2.0, "db:conflict") }
        fixes.forEach { add(it, 1.5, "db:fix") }
        crashes.forEach { add(it, 1.0, "db:crash") }
    }

    fun named(files: Collection<String>) = files.forEach { add(it, 2.0, "named") }

    fun trial(enabled: Set<String>, total: Int, verdict: Verdict) {
        if (enabled.isEmpty()) return
        when (verdict) {
            Verdict.REPRO -> {
                val w = ln(maxOf(total, enabled.size + 1).toDouble() / enabled.size).coerceIn(0.1, 3.0)
                enabled.forEach { score[it] = (score[it] ?: 0.0) + w }
            }
            Verdict.PASS -> enabled.forEach { score[it] = (score[it] ?: 0.0) - PASS_PENALTY }
            Verdict.UNKNOWN -> {}
        }
    }

    fun rank(files: List<String>): List<String> {
        val pos = files.withIndex().associate { it.value to it.index }
        return files.sortedWith(compareByDescending<String> { of(it) }.thenBy { pos[it] ?: 0 })
    }

    fun top(files: Collection<String>, min: Double = 1.0, limit: Int = 8): List<String> =
        rank(files.toList()).filter { of(it) >= min }.take(limit)

    companion object {
        const val OTHER_SIDE = 0.6
        const val PASS_PENALTY = 2.0
    }
}

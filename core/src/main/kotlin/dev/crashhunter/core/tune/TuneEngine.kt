package dev.crashhunter.core.tune

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.fix.RemoteFile
import kotlinx.serialization.Serializable

@Serializable
enum class ModPolicy { REMOVABLE, KEEP, PIN }

@Serializable
data class ModRule(
    val policy: ModPolicy = ModPolicy.REMOVABLE,
    val allowed: List<String>? = null,
    val allowedLabels: List<String> = emptyList(),
) {
    val hasRange: Boolean get() = allowed != null
    val isDefault: Boolean get() = policy == ModPolicy.REMOVABLE && !hasRange
}

const val LOADER_KEY = "@loader"

fun modKey(jar: dev.crashhunter.core.mods.ModJar): String = jar.primaryId ?: jar.fileName

fun inRange(entries: List<dev.crashhunter.core.fix.VersionEntry>, rule: ModRule): List<dev.crashhunter.core.fix.VersionEntry> {
    val allowed = rule.allowed?.toSet() ?: return entries
    return entries.filter { it.remote.fileId in allowed }
}

fun closestFirst(all: List<String>, current: String?, allowed: Collection<String>?): List<String> {
    val ok = all.filter { (allowed == null || it in allowed) && it != current }
    val ci = current?.let { all.indexOf(it) }?.takeIf { it >= 0 } ?: return ok
    return ok.sortedWith(compareBy<String> { kotlin.math.abs(all.indexOf(it) - ci) }.thenBy { all.indexOf(it) })
}

sealed interface TuneFind {
    data object Launches : TuneFind
    data class Culprits(val files: List<String>, val fingerprint: CrashFingerprint?, val direct: Boolean = false, val removable: Boolean = true, val db: Boolean = false) : TuneFind
    data class Stop(val status: TuneStatus, val message: String = "") : TuneFind
    data class WithFixes(val fixes: List<TuneStep>, val then: TuneFind) : TuneFind
}

enum class TuneStatus { LAUNCHED, ENVIRONMENT, BLOCKED, NOT_A_MOD, START_FAILED, INCONCLUSIVE, LIMIT, CANCELLED, ERROR }

@Serializable
sealed interface TuneStep {
    val round: Int
}

@Serializable
@kotlinx.serialization.SerialName("replaced")
data class Replaced(override val round: Int, val file: String, val newFile: String, val remote: RemoteFile, val culprits: List<String>, val launched: Boolean) : TuneStep

@Serializable
@kotlinx.serialization.SerialName("removed")
data class Removed(override val round: Int, val file: String, val alsoRemoved: List<String>, val culprits: List<String>) : TuneStep

@Serializable
@kotlinx.serialization.SerialName("loader")
data class LoaderChanged(override val round: Int, val uid: String, val from: String, val to: String, val launched: Boolean) : TuneStep

@Serializable
@kotlinx.serialization.SerialName("fixed")
data class Fixed(
    override val round: Int,
    val kind: dev.crashhunter.core.fix.FixKind,
    val target: String,
    val params: Map<String, String>,
    val actions: List<dev.crashhunter.core.fix.FixAction>,
    val launched: Boolean,
) : TuneStep

@Serializable
data class PendingState(val removed: Removed, val disabled: List<String>)

@Serializable
data class EngineState(
    val round: Int,
    val steps: List<TuneStep>,
    val pending: List<PendingState> = emptyList(),
    val policy: Map<String, ModPolicy> = emptyMap(),
    val loaderTried: Boolean = false,
    val loaderNow: String? = null,
    val innocent: List<String> = emptyList(),
    val lastRemoved: Removed? = null,
    val lastDeps: List<String> = emptyList(),
    val lastFingerprint: CrashFingerprint? = null,
)

data class TuneResult(val status: TuneStatus, val steps: List<TuneStep>, val blockedBy: List<String> = emptyList(), val message: String = "")

class ReplaceDone(val newFile: String, val undo: suspend () -> Unit)

sealed interface TuneEvent {
    data class Round(val number: Int) : TuneEvent
    data class Found(val culprits: List<String>, val direct: Boolean = false, val db: Boolean = false) : TuneEvent
    data class TryVersion(val file: String, val label: String, val index: Int, val count: Int) : TuneEvent
    data class VersionResult(val file: String, val label: String, val result: TrialResult, val newFailure: Boolean) : TuneEvent
    data class NoVersions(val file: String) : TuneEvent
    data class RemovedMods(val file: String, val also: List<String>) : TuneEvent
    data class Ineffective(val file: String, val also: List<String>) : TuneEvent
    data class TryLoader(val version: String, val index: Int, val count: Int) : TuneEvent
    data class TryFix(val kind: dev.crashhunter.core.fix.FixKind, val target: String, val params: Map<String, String>) : TuneEvent
    data class FixResult(val kind: dev.crashhunter.core.fix.FixKind, val target: String, val result: TrialResult, val newFailure: Boolean) : TuneEvent
    data class Fixed(val step: dev.crashhunter.core.tune.Fixed) : TuneEvent
    data class LoaderResult(val version: String, val result: TrialResult, val newFailure: Boolean) : TuneEvent
    data class RestoreStart(val files: List<String>) : TuneEvent
    data class TryRestore(val file: String, val label: String, val index: Int, val count: Int) : TuneEvent
    data class RestoreResult(val file: String, val label: String, val result: TrialResult) : TuneEvent
    data class Restored(val files: List<String>, val file: String, val newFile: String, val label: String) : TuneEvent
}

class LoaderOps(
    val uid: String,
    val current: String,
    val policy: ModPolicy,
    val candidates: suspend () -> List<String>,
    val set: suspend (String) -> ReplaceDone?,
)

class TuneEngine(
    initialPolicy: Map<String, ModPolicy>,
    private val find: suspend (round: Int) -> TuneFind,
    private val versions: suspend (file: String) -> List<RemoteFile>,
    private val replace: suspend (file: String, remote: RemoteFile) -> ReplaceDone?,
    private val launch: suspend () -> TrialOutcome,
    private val dependents: (file: String) -> Set<String>,
    private val disable: (files: Set<String>) -> Unit,
    private val sameFailure: (CrashFingerprint, CrashFingerprint) -> Boolean,
    private val listener: (TuneEvent) -> Unit = {},
    private val maxRounds: Int = 20,
    private val maxVersions: Int = 3,
    private val loader: LoaderOps? = null,
    private val removeFirst: Boolean = false,
    private val enable: (files: Set<String>) -> Unit = {},
    private val resume: EngineState? = null,
    private val checkpoint: (EngineState) -> Unit = {},
) {
    private class Pending(val removed: Removed, val disabled: Set<String>)
    private val pending = mutableListOf<Pending>()
    private var loaderTried = false
    private var loaderNow: String? = loader?.current
    private val policy = HashMap(initialPolicy)
    private fun policyOf(file: String) = policy[file] ?: ModPolicy.REMOVABLE
    private var stopped: TuneResult? = null
    private class LastRemoval(val removed: Removed, val deps: Set<String>, val fingerprint: CrashFingerprint?, val others: Set<String>)
    private var lastRemoval: LastRemoval? = null
    private val innocent = HashSet<String>()

    private fun mismatch(fp: CrashFingerprint?): Boolean {
        val e = fp?.exception ?: return false
        return e == "cause:CLASS_MISMATCH" || MISMATCH_ERRORS.any { e.endsWith(it) }
    }

    private fun undoIneffective(found: TuneFind.Culprits, steps: MutableList<TuneStep>) {
        val prev = lastRemoval ?: return
        lastRemoval = null
        val a = prev.fingerprint
        val b = found.fingerprint
        val same = if (a != null && b != null) sameFailure(a, b) && found.files.any { it in prev.others }
            else found.files.count { it in prev.others } >= 2
        if (!same) return
        enable(prev.deps)
        pending.removeAll { it.removed == prev.removed }
        steps.remove(prev.removed)
        innocent += prev.removed.file
        listener(TuneEvent.Ineffective(prev.removed.file, prev.removed.alsoRemoved))
    }

    init {
        resume?.let { r ->
            policy.putAll(r.policy)
            loaderTried = r.loaderTried
            r.loaderNow?.let { loaderNow = it }
            r.pending.forEach { pending += Pending(it.removed, it.disabled.toSet()) }
            innocent += r.innocent
            val last = r.lastRemoved ?: r.steps.lastOrNull() as? Removed
            if (last != null) {
                val deps = if (r.lastRemoved != null) r.lastDeps.toSet() else r.pending.firstOrNull { it.removed == last }?.disabled?.toSet() ?: (setOf(last.file) + last.alsoRemoved)
                lastRemoval = LastRemoval(last, deps, r.lastFingerprint, last.culprits.toSet() - last.file)
            }
        }
    }

    fun state(round: Int, steps: List<TuneStep>) = EngineState(round, steps.toList(), pending.map { PendingState(it.removed, it.disabled.sorted()) },
        HashMap(policy), loaderTried, loaderNow, innocent.sorted(), lastRemoval?.removed, lastRemoval?.deps?.sorted() ?: emptyList(), lastRemoval?.fingerprint)

    suspend fun run(): TuneResult {
        val steps = (resume?.steps ?: emptyList()).toMutableList()
        var current = resume?.round ?: 1
        try {
            return loop(steps) { current = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            checkpoint(state(current, steps))
            throw e
        }
    }

    private fun stop(o: TrialOutcome): Boolean {
        if (o.result != TrialResult.START_FAILED && o.result != TrialResult.CANCELLED) return false
        stopped = TuneResult(if (o.result == TrialResult.CANCELLED) TuneStatus.CANCELLED else TuneStatus.START_FAILED, emptyList(), message = o.note ?: "")
        return true
    }

    private fun stoppedWith(steps: List<TuneStep>): TuneResult = stopped!!.copy(steps = steps.toList())

    private suspend fun loop(steps: MutableList<TuneStep>, at: (Int) -> Unit): TuneResult {
        val from = resume?.round ?: 1
        for (round in from..maxRounds) {
            at(round)
            checkpoint(state(round, steps))
            listener(TuneEvent.Round(round))
            var first = find(round)
            if (first is TuneFind.WithFixes) {
                steps += first.fixes
                first.fixes.forEach { listener(TuneEvent.Fixed(it as Fixed)) }
                first = first.then
            }
            val found = when (val f = first) {
                is TuneFind.WithFixes -> return TuneResult(TuneStatus.ERROR, steps)
                TuneFind.Launches -> { if (!restore(steps)) { checkpoint(state(round, steps)); return stoppedWith(steps) }; return TuneResult(TuneStatus.LAUNCHED, steps) }
                is TuneFind.Stop -> {
                    if (f.status == TuneStatus.NOT_A_MOD) when (tryLoader(round, null, steps)) {
                        VersionOutcome.LAUNCHED -> return TuneResult(TuneStatus.LAUNCHED, steps)
                        VersionOutcome.PROGRESS -> continue
                        VersionOutcome.STOPPED -> { checkpoint(state(round, steps)); return stoppedWith(steps) }
                        VersionOutcome.NONE -> {}
                    }
                    checkpoint(state(round, steps))
                    return TuneResult(f.status, steps, message = f.message)
                }
                is TuneFind.Culprits -> f
            }
            if (found.files.isEmpty()) return TuneResult(TuneStatus.INCONCLUSIVE, steps)
            undoIneffective(found, steps)
            listener(TuneEvent.Found(found.files, found.direct, found.db))
            if (found.direct && found.files.all { policyOf(it) == ModPolicy.PIN }) return TuneResult(TuneStatus.BLOCKED, steps, blockedBy = found.files)
            val options = found.files.filter { policyOf(it) == ModPolicy.REMOVABLE && it !in innocent }
                .map { it to dependents(it) }
                .filter { (_, deps) -> deps.all { policyOf(it) == ModPolicy.REMOVABLE } }
                .sortedBy { (_, deps) -> deps.size }
            val early = removeFirst && found.removable && !found.db && options.isNotEmpty() && !mismatch(found.fingerprint)
            if (!early) {
                when (tryVersions(round, found, steps)) {
                    VersionOutcome.LAUNCHED -> { if (!restore(steps)) { checkpoint(state(round, steps)); return stoppedWith(steps) }; return TuneResult(TuneStatus.LAUNCHED, steps) }
                    VersionOutcome.PROGRESS -> continue
                    VersionOutcome.STOPPED -> { checkpoint(state(round, steps)); return stoppedWith(steps) }
                    VersionOutcome.NONE -> {}
                }
                if (!found.removable) continue
                if (!found.direct && !removeFirst) when (tryLoader(round, found.fingerprint, steps)) {
                    VersionOutcome.LAUNCHED -> return TuneResult(TuneStatus.LAUNCHED, steps)
                    VersionOutcome.PROGRESS -> continue
                    VersionOutcome.STOPPED -> { checkpoint(state(round, steps)); return stoppedWith(steps) }
                    VersionOutcome.NONE -> {}
                }
            }
            val (pick, deps) = options.firstOrNull()
                ?: return TuneResult(TuneStatus.BLOCKED, steps, blockedBy = found.files)
            disable(deps)
            val also = deps.filter { it != pick }
            val removed = Removed(round, pick, also, found.files)
            steps += removed
            if (early) pending += Pending(removed, deps)
            if (found.removable && !found.db) lastRemoval = LastRemoval(removed, deps, found.fingerprint, found.files.toSet() - pick)
            listener(TuneEvent.RemovedMods(pick, also))
        }
        return TuneResult(TuneStatus.LIMIT, steps)
    }

    private enum class VersionOutcome { LAUNCHED, PROGRESS, NONE, STOPPED }

    private suspend fun guarded(undo: suspend () -> Unit): TrialOutcome = try {
        launch()
    } catch (e: kotlinx.coroutines.CancellationException) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { undo() }
        throw e
    }

    private suspend fun tryVersions(round: Int, found: TuneFind.Culprits, steps: MutableList<TuneStep>): VersionOutcome {
        for (file in found.files) {
            if (policyOf(file) == ModPolicy.PIN) continue
            val alts = runCatching { versions(file) }.getOrDefault(emptyList()).filter { it.fileName != file }.take(maxVersions)
            if (alts.isEmpty()) { listener(TuneEvent.NoVersions(file)); continue }
            for ((i, alt) in alts.withIndex()) {
                listener(TuneEvent.TryVersion(file, alt.versionLabel, i + 1, alts.size))
                val done = runCatching { replace(file, alt) }.getOrNull() ?: continue
                val o = guarded { done.undo() }
                if (stop(o)) { done.undo(); return VersionOutcome.STOPPED }
                val newFailure = o.result == TrialResult.FAIL && (if (found.fingerprint == null) found.db else o.fingerprint != null &&
                    !sameFailure(found.fingerprint, o.fingerprint))
                listener(TuneEvent.VersionResult(file, alt.versionLabel, o.result, newFailure))
                when {
                    o.result == TrialResult.PASS -> {
                        carry(file, done.newFile)
                        steps += Replaced(round, file, done.newFile, alt, found.files, true)
                        return VersionOutcome.LAUNCHED
                    }
                    newFailure -> {
                        carry(file, done.newFile)
                        steps += Replaced(round, file, done.newFile, alt, found.files, false)
                        return VersionOutcome.PROGRESS
                    }
                    else -> done.undo()
                }
            }
        }
        return VersionOutcome.NONE
    }

    private suspend fun tryLoader(round: Int, fp: CrashFingerprint?, steps: MutableList<TuneStep>): VersionOutcome {
        val l = loader ?: return VersionOutcome.NONE
        if (loaderTried || l.policy == ModPolicy.PIN) return VersionOutcome.NONE
        loaderTried = true
        val versions = runCatching { l.candidates() }.getOrDefault(emptyList()).take(maxVersions)
        for ((i, v) in versions.withIndex()) {
            listener(TuneEvent.TryLoader(v, i + 1, versions.size))
            val done = runCatching { l.set(v) }.getOrNull() ?: continue
            val o = guarded { done.undo() }
            if (stop(o)) { done.undo(); loaderTried = false; return VersionOutcome.STOPPED }
            val newFailure = o.result == TrialResult.FAIL && fp != null && o.fingerprint != null && !sameFailure(fp, o.fingerprint)
            listener(TuneEvent.LoaderResult(v, o.result, newFailure))
            val from = loaderNow ?: l.current
            when {
                o.result == TrialResult.PASS -> { steps += LoaderChanged(round, l.uid, from, v, true); loaderNow = v; return VersionOutcome.LAUNCHED }
                newFailure -> { steps += LoaderChanged(round, l.uid, from, v, false); loaderNow = v; return VersionOutcome.PROGRESS }
                else -> done.undo()
            }
        }
        return VersionOutcome.NONE
    }

    private suspend fun restore(steps: MutableList<TuneStep>): Boolean {
        if (pending.isEmpty()) return true
        listener(TuneEvent.RestoreStart(pending.map { it.removed.file }))
        for (p in pending.toList()) {
            val pick = p.removed.file
            val others = p.removed.culprits.filter { it != pick && policyOf(it) != ModPolicy.PIN }
            var done = false
            for (target in listOf(pick) + others) {
                if (done) break
                val alts = runCatching { versions(target) }.getOrDefault(emptyList()).filter { it.fileName != target }.take(maxVersions)
                for ((i, alt) in alts.withIndex()) {
                    listener(TuneEvent.TryRestore(target, alt.versionLabel, i + 1, alts.size))
                    enable(p.disabled)
                    val swap = runCatching { replace(target, alt) }.getOrNull()
                    if (swap == null) { disable(p.disabled); continue }
                    val o = guarded { swap.undo(); disable(p.disabled) }
                    if (stop(o)) { swap.undo(); disable(p.disabled); return false }
                    listener(TuneEvent.RestoreResult(target, alt.versionLabel, o.result))
                    if (o.result == TrialResult.PASS) {
                        carry(target, swap.newFile)
                        steps.remove(p.removed)
                        steps += Replaced(p.removed.round, target, swap.newFile, alt, p.removed.culprits, true)
                        listener(TuneEvent.Restored(p.disabled.toList(), target, swap.newFile, alt.versionLabel))
                        done = true
                        break
                    }
                    swap.undo()
                    disable(p.disabled)
                }
            }
            pending.remove(p)
        }
        return true
    }

    private fun carry(from: String, to: String) {
        policy[from]?.let { policy[to] = it }
    }
}

private val MISMATCH_ERRORS = listOf("IncompatibleClassChangeError", "NoSuchMethodError", "NoSuchFieldError", "AbstractMethodError", "NoClassDefFoundError", "VerifyError")

package dev.crashhunter.core.fix

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.StackTraceTools
import dev.crashhunter.core.bisect.ModToggler
import dev.crashhunter.core.bisect.PrismTrialRunner
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.TrialSettings
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.launcher.GameLauncher
import java.io.File

enum class VerifyResult { FIXED, SAME_FAILURE, NEW_FAILURE, NOT_STARTED, SKIPPED }

data class FixOutcome(val record: FixRecord, val verify: VerifyResult, val outcome: TrialOutcome?, val records: List<FixRecord> = listOf(record)) {
    val rounds: Int get() = records.size
}

fun interface TrialFactory {
    fun create(instance: GameInstance, fingerprintOf: (CrashSource) -> CrashFingerprint?, status: (String) -> Unit): suspend () -> TrialOutcome
}

data class Replan(val proposals: List<FixProposal>, val before: CrashFingerprint?)

fun interface Replanner {
    suspend fun next(instance: GameInstance, outcome: TrialOutcome): Replan?
}

class FixSession(
    private val applier: FixApplier,
    private val trials: TrialFactory?,
    private val fingerprintOf: (CrashSource) -> CrashFingerprint?,
    private val status: (String) -> Unit = {},
) {
    suspend fun run(instance: GameInstance, proposals: List<FixProposal>, before: CrashFingerprint?, verify: Boolean): FixOutcome =
        runOnce(instance, proposals, before, verify, null, 1)

    suspend fun runChain(instance: GameInstance, proposals: List<FixProposal>, before: CrashFingerprint?, verify: Boolean, maxRounds: Int, replanner: Replanner?): FixOutcome {
        if (!verify || trials == null || replanner == null || maxRounds <= 1) return run(instance, proposals, before, verify)
        val chainId = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val records = mutableListOf<FixRecord>()
        val applied = mutableSetOf<String>()
        val fromDb = proposals.filter { it.fromDb }
        var props = if (fromDb.isNotEmpty() && fromDb.size < proposals.size) fromDb else proposals
        var pending = proposals.filter { it !in props }
        var fp = before
        var round = 1
        var last: FixOutcome? = null
        while (true) {
            if (round > 1) status("round:$round")
            val out = runOnce(instance, props, fp, verify, chainId, round)
            last = out
            records += out.record
            applied += props.map { it.id }
            val kept = records.any { it.state == FixState.KEPT_NEW_FAILURE || it.state == FixState.VERIFIED }
            val failed = out.verify == VerifyResult.SAME_FAILURE || out.verify == VerifyResult.NOT_STARTED
            val overall = if (failed && kept) VerifyResult.NEW_FAILURE else out.verify
            if (out.verify == VerifyResult.FIXED || out.verify == VerifyResult.SKIPPED || round >= maxRounds) return out.copy(verify = overall, records = records.toList())
            val next: List<FixProposal>
            if (failed) {
                if (pending.isNotEmpty()) { next = pending; pending = emptyList() }
                else if (props.any { it.fromDb }) {
                    status("replan")
                    next = runCatching { out.outcome?.let { replanner.next(instance, it) } }.getOrNull()?.proposals.orEmpty()
                        .filter { it.id !in applied && it.actions.isNotEmpty() && !it.fromDb }
                } else return out.copy(verify = overall, records = records.toList())
            } else {
                status("replan")
                val r = runCatching { out.outcome?.let { replanner.next(instance, it) } }.getOrNull()
                pending = emptyList()
                next = r?.proposals.orEmpty().filter { it.id !in applied && it.actions.isNotEmpty() }
                fp = r?.before ?: out.outcome?.fingerprint
            }
            if (next.isEmpty()) return out.copy(verify = overall, records = records.toList())
            val nextDb = next.filter { it.fromDb }
            props = if (nextDb.isNotEmpty() && nextDb.size < next.size) nextDb else next
            pending = pending + next.filter { it !in props }
            round++
        }
    }

    private suspend fun runOnce(instance: GameInstance, proposals: List<FixProposal>, before: CrashFingerprint?, verify: Boolean, chainId: String?, round: Int): FixOutcome {
        status("apply")
        val record = applier.apply(instance, proposals.filter { it.actions.isNotEmpty() }, chainId, round)
        if (!verify || trials == null) return FixOutcome(record, VerifyResult.SKIPPED, null)
        status("verify")
        applier.mark(record, FixState.VERIFYING)
        val outcome = trials.create(instance, fingerprintOf, status)()
        val result = when (outcome.result) {
            TrialResult.PASS -> VerifyResult.FIXED
            TrialResult.FAIL -> if (before != null && outcome.fingerprint != null && StackTraceTools.sameFailure(before, outcome.fingerprint)) VerifyResult.SAME_FAILURE else VerifyResult.NEW_FAILURE
            TrialResult.HANG, TrialResult.START_FAILED, TrialResult.CANCELLED -> VerifyResult.NOT_STARTED
        }
        val finalRecord = when (result) {
            VerifyResult.FIXED -> applier.mark(record, FixState.VERIFIED)
            VerifyResult.NEW_FAILURE -> applier.mark(record, FixState.KEPT_NEW_FAILURE, outcome.note)
            VerifyResult.SAME_FAILURE, VerifyResult.NOT_STARTED -> applier.undo(record).let { applier.mark(it, FixState.UNDONE, "${result.name} ${outcome.note}".trim()) }
            VerifyResult.SKIPPED -> record
        }
        return FixOutcome(finalRecord, result, outcome)
    }

    companion object {
        fun prismTrials(launcher: GameLauncher, settings: TrialSettings, journalFile: File): TrialFactory = TrialFactory { instance, fp, status ->
            { PrismTrialRunner(launcher, instance, ModToggler(instance.modsDir, journalFile), emptyList(), settings, fp, status = status, tracker = dev.crashhunter.core.launcher.GameTracker(dev.crashhunter.core.util.AppDirs.gamesFile)).run(emptySet())
                .also { runCatching { launcher.closeLaunchers(listOf(instance.id)) } } }
        }
    }
}

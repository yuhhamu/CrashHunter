package dev.crashhunter.core.bisect

import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.DependencyGraph
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar
import dev.crashhunter.core.launcher.Launcher
import dev.crashhunter.core.util.AppDirs
import dev.crashhunter.core.util.AppSettings
import java.io.File

sealed interface BisectProgress {
    data class Preparing(val step: String) : BisectProgress
    data class Trial(val number: Int, val estimate: Int, val enabledCount: Int, val total: Int, val phase: String, val state: String, val slot: Int = 0, val pool: Int = 0,
                     val enabled: Set<String> = emptySet(), val focus: List<String> = emptyList()) : BisectProgress
    data class TrialDone(val number: Int, val result: TrialResult, val slot: Int = 0) : BisectProgress
    data class Parallel(val count: Int, val reason: String = "", val instances: List<String> = emptyList()) : BisectProgress
    data class Mods(val names: Map<String, String>, val candidates: List<String>) : BisectProgress
    data object WaitingForUser : BisectProgress
    data class Log(val key: String, val args: List<String> = emptyList(), val slot: Int? = null) : BisectProgress
}

data class BisectReport(
    val result: BisectResult,
    val culprits: List<ModJar>,
    val requirements: List<ModJar>,
    val target: GameInstance,
    val workCopy: Boolean,
    val crashDiagnosis: Diagnosis?,
)

class BisectSession(
    private val launcher: Launcher,
    private val source: GameInstance,
    private val settings: AppSettings,
    private val diagnosisService: DiagnosisService,
    private val diagnosis: Diagnosis?,
    val gate: ManualGate = ManualGate(),
    private val journalFile: File = AppDirs.journalFile,
    private val extraSuspects: List<String> = emptyList(),
    private val inPlace: Boolean = false,
    private val seed: Map<String, TrialOutcome> = emptyMap(),
    private val knownPass: MutableList<Set<String>> = mutableListOf(),
    private val recorder: dev.crashhunter.core.db.RunRecorder? = null,
    private val rank: ((List<String>) -> List<String>)? = null,
    private val onVerdict: (Set<String>, Verdict) -> Unit = { _, _ -> },
    private val minRepeats: Int = 1,
    private val progress: (BisectProgress) -> Unit = {},
) {
    private val runLog = File(AppDirs.logDir, "bisect-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())}.log")
    private fun log(line: String) = runCatching {
        runLog.parentFile.mkdirs()
        runLog.appendText("${java.time.LocalTime.now().withNano(0)} $line\n")
    }

    private var multi = false

    private fun event(key: String, vararg args: Any?) = slotEvent(null, key, *args)

    private fun slotEvent(slot: Int?, key: String, vararg args: Any?) {
        val a = args.map { it?.toString() ?: "" }
        val tag = if (slot != null && multi) "#${slot + 1} " else ""
        log("$tag$key ${a.joinToString(" | ")}")
        progress(BisectProgress.Log(key, a, if (multi) slot else null))
    }

    val logFile: File get() = runLog

    suspend fun run(): BisectReport {
        log("start instance=${source.id} workCopy=${settings.useWorkCopy} mode=${if (settings.manualJudge) "manual" else "auto"} target=${diagnosis?.fingerprint}")
        val useCopy = settings.useWorkCopy
        event("start", source.name, if (settings.manualJudge) "manual" else "auto", diagnosis?.title ?: "")
        val target = if (useCopy) {
            progress(BisectProgress.Preparing("copy"))
            WorkCopy.create(source, settings.includeSaves) { progress(BisectProgress.Preparing("copy:$it")) }
                .also { event("copy", it.id) }
                .also { c -> dev.crashhunter.core.mods.CrashTools.disable(c.modsDir).takeIf { it.isNotEmpty() }?.let { event("toolOff", it.joinToString(", ")) } }
        } else source.also { event("direct", it.id) }
        progress(BisectProgress.Preparing("mods"))
        val index = ModIndex.load(target.modsDir)
        val graph = DependencyGraph(index, target.profile.loader)
        val candidates = index.jars.filter { it.enabled }.map { it.fileName }
        val toggler = ModToggler(target.modsDir, journalFile)

        val previous = ModToggler.read(journalFile)
        if (previous != null) ModToggler.restore(journalFile)
        val reuse = previous?.takeIf { it.instanceId == target.id && it.candidates == candidates }?.cache ?: emptyMap()
        var journal = toggler.begin(target.id, useCopy).copy(candidates = candidates, cache = reuse)
        toggler.save(journal)

        val suspects = ((diagnosis?.suspects?.mapNotNull { s -> s.jar?.let { j -> index.byFileName(j)?.fileName } } ?: emptyList()) +
            extraSuspects.mapNotNull { index.byFileName(it)?.fileName }).distinct().filter { it in candidates }.let { rank?.invoke(it) ?: it }
        fun nameOf(f: String) = index.byFileName(f)?.displayName ?: f
        fun names(list: Collection<String>) = list.joinToString(", ") { nameOf(it) }
        event("mods", candidates.size)
        if (suspects.isNotEmpty()) event("suspects", names(suspects))
        if (reuse.isNotEmpty()) event("resume", reuse.size)
        val controller = launcher.controller(AppDirs.logDir)
        val trialSettings = TrialSettings(
            mode = if (settings.manualJudge) JudgeMode.MANUAL else JudgeMode.AUTO,
            startTimeoutSec = settings.startTimeoutSec,
            timeoutSec = settings.timeoutSec,
            settleOverrideSec = settings.settleSec,
        )
        val parallel = if ((!useCopy && !inPlace) || trialSettings.mode == JudgeMode.MANUAL) 1
            else Parallelism.choose(settings.parallelTrials, settings.maxParallel, Parallelism.instanceMemoryMb(source, launcher.installation.dataDir), Parallelism.freeMemoryMb())
        multi = parallel > 1
        val minimize = settings.minimizeGames && trialSettings.mode == JudgeMode.AUTO
        fun titleFor(slot: Int) = if (parallel > 1) "CrashHunter #${slot + 1} - " else "CrashHunter - "
        val current = java.util.concurrent.ConcurrentHashMap<Int, BisectProgress.Trial>()
        fun currentOf(slot: Int) = current[slot] ?: BisectProgress.Trial(0, 0, 0, candidates.size, "baseline", "", slot)
        fun slotState(slot: Int, st: String) {
            recorder?.state(slot, st)
            slotEvent(slot, "state.$st")
            val c = currentOf(slot).copy(state = st)
            current[slot] = c
            progress(c)
        }
        var lastFailSource: dev.crashhunter.core.analyze.CrashSource? = null
        val lastCrashText = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val tracker = dev.crashhunter.core.launcher.GameTracker(AppDirs.gamesFile)
        val extraCopies = mutableListOf<GameInstance>()
        val stallDir = java.io.File(dev.crashhunter.core.util.AppDirs.dataDir, "logs")
        val guardAll: () -> Unit = {
            val changed = (listOf(target) + extraCopies).count { runCatching { LoadGuard.limitThreads(it) }.getOrDefault(false) }
            if (changed > 0) event("loadGuard")
        }
        val runner = PrismTrialRunner(
            controller, target, toggler, candidates, trialSettings,
            fingerprintOf = { src ->
                lastFailSource = src
                val d = diagnosisService.diagnose(src, target, index)
                lastCrashText[0] = listOfNotNull(d.title, d.topSuspect?.name).joinToString(" — ")
                d.fingerprint
            },
            gate = gate,
            tracker = tracker,
            status = { st ->
                slotState(0, st)
                if (st == "running" && trialSettings.mode == JudgeMode.MANUAL) progress(BisectProgress.WaitingForUser)
            },
            windowTitle = titleFor(0),
            minimizeWindow = minimize,
            onStall = guardAll,
            dumpDir = stallDir,
        )
        val extras = mutableListOf<PrismTrialRunner>()
        for (slot in 1 until parallel) {
            progress(BisectProgress.Preparing("copy"))
            val copy = runCatching { WorkCopy.create(source, settings.includeSaves, slot) { progress(BisectProgress.Preparing("copy:$it")) } }.getOrNull() ?: break
            extraCopies += copy
            val copyIndex = ModIndex.load(copy.modsDir)
            val t = ModToggler(copy.modsDir, slotJournal(journalFile, slot))
            t.save(t.begin(copy.id, true).copy(candidates = candidates))
            extras += PrismTrialRunner(
                controller, copy, t, candidates, trialSettings,
                fingerprintOf = { src ->
                    lastFailSource = src
                    val d = diagnosisService.diagnose(src, copy, copyIndex)
                    lastCrashText[slot] = listOfNotNull(d.title, d.topSuspect?.name).joinToString(" — ")
                    d.fingerprint
                },
                tracker = tracker,
                status = { st -> slotState(slot, st) },
                windowTitle = titleFor(slot),
                minimizeWindow = minimize,
                onStall = guardAll,
                dumpDir = stallDir,
            )
        }
        multi = extras.isNotEmpty()
        if (extras.isNotEmpty()) event("parallel", extras.size + 1)
        progress(BisectProgress.Mods(index.jars.associate { it.fileName to it.displayName }, candidates))
        progress(BisectProgress.Parallel(extras.size + 1, instances = listOf(target.id) + extraCopies.map { it.id }))
        recorder?.let { r -> r.parallel = maxOf(r.parallel, extras.size + 1); r.suspects += suspects }
        val cache = LinkedHashMap(reuse)
        seed.forEach { (k, v) -> cache.putIfAbsent(k, v) }
        val engine = BisectEngine(
            candidates = suspects + candidates.filter { it !in suspects },
            runner = runner,
            withRequirements = { graph.withRequirements(it) },
            suspects = suspects,
            target = if (settings.anyCrash || settings.manualJudge) null else diagnosis?.fingerprint,
            anyCrash = settings.anyCrash || settings.manualJudge,
            hangIsFailure = settings.hangIsFailure,
            repeats = maxOf(settings.repeats, minRepeats),
            onVerdict = onVerdict,
            cache = cache,
            extraRunners = extras,
            knownPass = knownPass,
            listener = { e ->
                if (e.outcome == null) {
                    val c = BisectProgress.Trial(e.number, e.estimate, e.enabledCount, candidates.size, e.phase, "", e.slot, e.pool, e.enabled, e.focus)
                    recorder?.let { r -> r.start(e.number, e.slot, e.phase, e.enabledCount, e.pool); r.estimate = maxOf(r.estimate, e.estimate) }
                    current[e.slot] = c
                    lastCrashText.remove(e.slot)
                    val off = candidates.filter { it !in e.enabled }
                    val which = when {
                        e.enabled.isEmpty() || off.isEmpty() -> ""
                        e.enabled.size <= 6 -> "on: " + names(e.enabled)
                        off.size <= 6 -> "off: " + names(off)
                        else -> ""
                    }
                    slotEvent(e.slot, "trial", e.number, e.phase, e.enabledCount, candidates.size, which)
                    progress(c)
                } else {
                    recorder?.done(e.number, e.outcome.result.name, e.outcome.fingerprint)
                    log("trial ${e.number} [${e.phase}] enabled=${e.enabledCount}/${candidates.size} -> ${e.outcome.result} ${e.outcome.note} ${e.outcome.fingerprint ?: ""}")
                    slotEvent(e.slot, "done.${e.outcome.result}", e.number, (lastCrashText[e.slot] ?: "").ifEmpty { e.outcome.note })
                    progress(BisectProgress.TrialDone(e.number, e.outcome.result, e.slot))
                    journal = journal.copy(cache = LinkedHashMap(cache))
                    runCatching { toggler.save(journal) }
                }
            },
        )
        val result = try {
            engine.run()
        } catch (e: Exception) {
            BisectResult(BisectStatus.ERROR, message = e.message ?: e.javaClass.simpleName)
        } finally {
            progress(BisectProgress.Preparing("restore:1/$parallel"))
            val n = ModToggler.restore(journalFile)
            for (slot in 1 until parallel) {
                progress(BisectProgress.Preparing("restore:${slot + 1}/$parallel"))
                val jf = slotJournal(journalFile, slot); if (jf.isFile) ModToggler.restore(jf)
            }
            runCatching { controller.closeLaunchers(listOf(target.id) + extraCopies.map { it.id }) }
            if (extraCopies.isNotEmpty()) progress(BisectProgress.Preparing("cleanup"))
            extraCopies.forEach { runCatching { WorkCopy.delete(it.dir) } }
            event("restore", n)
        }
        if (engine.inferred > 0) event("inferred", engine.inferred)
        if (engine.unknowns > 0) event("unknowns", engine.unknowns)
        log("result ${result.status} culprits=${result.culprits} requirements=${result.requirements} trials=${result.trials} ${result.message}")
        event("result.${result.status}", names(result.culprits), result.trials)
        val byName = index.jars.associateBy { it.fileName }
        val crash = lastFailSource?.let { runCatching { diagnosisService.diagnose(it, target, index) }.getOrNull() }
        return BisectReport(result, result.culprits.mapNotNull { byName[it] }, result.requirements.mapNotNull { byName[it] }, target, useCopy, crash)
    }

    companion object {
        fun slotJournal(journalFile: File, slot: Int): File = File(journalFile.parentFile, journalFile.nameWithoutExtension + "-${slot + 1}." + journalFile.extension)

        fun restorePending(journalFile: File = AppDirs.journalFile): Int {
            var n = if (journalFile.isFile) ModToggler.restore(journalFile) else 0
            val prefix = journalFile.nameWithoutExtension + "-"
            journalFile.parentFile?.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith("." + journalFile.extension) }
                ?.forEach { n += runCatching { ModToggler.restore(it) }.getOrDefault(0) }
            return n
        }
    }
}

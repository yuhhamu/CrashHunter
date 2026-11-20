package dev.crashhunter.core.tune

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.analyze.StackTraceTools
import dev.crashhunter.core.bisect.BisectProgress
import dev.crashhunter.core.bisect.BisectSession
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.JudgeMode
import dev.crashhunter.core.bisect.LoadGuard
import dev.crashhunter.core.bisect.ModToggler
import dev.crashhunter.core.bisect.PrismTrialRunner
import dev.crashhunter.core.bisect.TrialSettings
import dev.crashhunter.core.bisect.WorkCopy
import dev.crashhunter.core.fix.DisableMod
import dev.crashhunter.core.fix.FileAdded
import dev.crashhunter.core.fix.FixApplier
import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.fix.FixProposal
import dev.crashhunter.core.fix.RemoteResolver
import dev.crashhunter.core.fix.ReplaceMod
import dev.crashhunter.core.fix.rangesRequiredBy
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.DependencyGraph
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.launcher.GameTracker
import dev.crashhunter.core.launcher.Launcher
import dev.crashhunter.core.util.AppDirs
import dev.crashhunter.core.util.AppSettings
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

sealed interface TuneProgress {
    data class Event(val event: TuneEvent) : TuneProgress
    data class Bisect(val progress: BisectProgress) : TuneProgress
    data class Phase(val key: String, val args: List<String> = emptyList()) : TuneProgress
    data class Db(val check: dev.crashhunter.core.db.DbCheck?, val plan: List<String>) : TuneProgress
    data class Launch(val instance: String, val enabled: Int) : TuneProgress
    data class Note(val key: String, val args: List<String> = emptyList(), val params: Map<String, String> = emptyMap()) : TuneProgress
    data class Mods(val names: Map<String, String>, val versions: Map<String, String>, val enabled: Set<String>) : TuneProgress
}

data class TuneReport(val result: TuneResult, val source: GameInstance, val copy: GameInstance?, val names: Map<String, String>, val versions: Map<String, String> = emptyMap(), val learned: List<dev.crashhunter.core.db.DbItem> = emptyList())

@kotlinx.serialization.Serializable
data class TuneCheckpoint(
    val sourceId: String,
    val copyDir: String,
    val time: Long,
    val engine: EngineState,
    val passSets: List<List<String>> = emptyList(),
    val staticDone: Boolean = false,
    val status: String? = null,
    val message: String = "",
)

class TuneResume(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(sourceId: String) = File(dir, sourceId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun save(c: TuneCheckpoint) {
        dir.mkdirs()
        val f = file(c.sourceId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(TuneCheckpoint.serializer(), c))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun load(sourceId: String): TuneCheckpoint? {
        val f = file(sourceId)
        if (!f.isFile) return null
        val c = runCatching { json.decodeFromString(TuneCheckpoint.serializer(), f.readText()) }.getOrNull() ?: return null
        val copy = File(c.copyDir)
        return c.takeIf { it.sourceId == sourceId && copy.isDirectory && WorkCopy.isWorkCopy(copy) }
    }

    fun mark(sourceId: String, status: TuneStatus, message: String) {
        val c = load(sourceId) ?: return
        save(c.copy(status = status.name, message = message, time = System.currentTimeMillis()))
    }

    fun clear(sourceId: String) { file(sourceId).delete() }

    companion object {
        val default: TuneResume get() = TuneResume(File(AppDirs.dataDir, "tune-resume"))
        val RESUMABLE = setOf(TuneStatus.START_FAILED, TuneStatus.CANCELLED, TuneStatus.ERROR)
    }
}

object TunePolicyStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private fun file(instanceId: String) = File(File(AppDirs.dataDir, "tune"), instanceId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    private val ser = kotlinx.serialization.builtins.MapSerializer(String.serializer(), ModRule.serializer())

    fun load(instanceId: String): Map<String, ModRule> = runCatching { json.decodeFromString(ser, file(instanceId).readText()) }.getOrDefault(emptyMap())

    fun save(instanceId: String, map: Map<String, ModRule>) {
        val f = file(instanceId)
        f.parentFile.mkdirs()
        f.writeText(json.encodeToString(ser, map.filterValues { !it.isDefault }))
    }
}

class TuneSession(
    private val launcher: Launcher,
    private val source: GameInstance,
    private val settings: AppSettings,
    private val diagnosisService: DiagnosisService,
    private val resolver: RemoteResolver?,
    private val workApplier: FixApplier,
    private val rules: Map<String, ModRule>,
    val recorder: dev.crashhunter.core.db.RunRecorder = dev.crashhunter.core.db.RunRecorder(),
    private val planner: ((Set<FixKind>) -> dev.crashhunter.core.fix.FixPlanner)? = null,
    private val dbFirst: (suspend (GameInstance) -> dev.crashhunter.core.db.DbCheck?)? = null,
    private val resume: TuneCheckpoint? = null,
    private val store: TuneResume? = null,
    private val progress: (TuneProgress) -> Unit = {},
) {
    suspend fun run(): TuneReport {
        progress(TuneProgress.Phase("copy"))
        val resumed = resume?.let { r -> runCatching { dev.crashhunter.core.launcher.Formats.of(source).read(File(r.copyDir)) }.getOrNull() }
        val copy = resumed ?: WorkCopy.create(source, settings.includeSaves, variant = "tune")
        if (resumed != null) {
            staticDone = resume!!.staticDone
            passSets += resume.passSets.map { it.toSet() }
            progress(TuneProgress.Note("resumed", listOf(resume.engine.round.toString())))
        } else {
            store?.clear(source.id)
            dev.crashhunter.core.mods.CrashTools.disable(copy.modsDir).takeIf { it.isNotEmpty() }?.let { progress(TuneProgress.Note("toolOff", listOf(it.joinToString(", ")))) }
        }
        val names = HashMap<String, String>()
        val versions = HashMap<String, String>()
        fun remember(index: ModIndex) {
            index.jars.forEach { j ->
                names[j.fileName] = j.displayName
                j.mods.firstOrNull()?.version?.takeIf { !it.contains("\${") }?.let { versions[j.fileName] = it }
            }
            progress(TuneProgress.Mods(HashMap(names), HashMap(versions), index.jars.filter { it.enabled }.map { it.fileName }.toSet()))
        }
        val fileRules = HashMap<String, ModRule>()
        ModIndex.load(copy.modsDir).also { remember(it) }.jars.forEach { j -> rules[modKey(j)]?.let { fileRules[j.fileName] = it } }
        val controller = launcher.controller(AppDirs.logDir)
        val tracker = GameTracker(AppDirs.gamesFile)
        val trialSettings = TrialSettings(JudgeMode.AUTO, settings.startTimeoutSec, settings.timeoutSec, settings.settleSec)
        val bisectSettings = settings.copy(useWorkCopy = false, manualJudge = false)
        val journal = File(AppDirs.dataDir, "tune-journal.json")
        dbFirst?.let { ask ->
            progress(TuneProgress.Phase("db"))
            val check = runCatching { ask(copy) }.getOrNull()
            dbPlan.clear()
            if (check != null) dbPlan += planFrom(check, ModIndex.load(copy.modsDir))
            dbBrokenRefs = check?.known?.broken?.map { it.mod }.orEmpty()
            recorder.notes += if (check == null) "db:failed" else "db:${check.known.broken.size}/${check.known.conflicts.size}/${check.known.fixes.size}/${check.crashes.size}"
            progress(TuneProgress.Db(check, (dbPre + dbPlan).flatMap { it.files }.distinct()))
        }
        prepareSuspicion(copy)
        val sourceSingle = LoadGuard.singleThreaded(source)

        val engine = TuneEngine(
            initialPolicy = fileRules.mapValues { it.value.policy },
            find = { round ->
                val enabledNow = enabledOf(copy)
                val key = dev.crashhunter.core.bisect.BisectEngine.keyOf(enabledNow)
                if (baseKey == null) baseKey = key
                val early = if (staticDone) emptyList() else { staticDone = true; staticFixes(round, copy, fileRules) }
                if (early.isNotEmpty()) { last = null; ModIndex.load(copy.modsDir).also { remember(it) } }
                nextDbBroken(copy)?.let { c -> last = null; return@TuneEngine if (early.isEmpty()) TuneFind.Culprits(c.files, null, direct = true, removable = false, db = true) else TuneFind.WithFixes(early, TuneFind.Culprits(c.files, null, direct = true, removable = false, db = true)) }
                var known = last?.takeIf { it.key == key } ?: run {
                    progress(TuneProgress.Phase("launch"))
                    launchAll(copy, controller, tracker, trialSettings, journal)
                }
                val fixes = early.toMutableList<TuneStep>()
                fun done(f: TuneFind): TuneFind = if (fixes.isEmpty()) f else TuneFind.WithFixes(fixes.toList(), f)
                for (attempt in 0 until 4) {
                    when (known.outcome.result) {
                        TrialResult.PASS -> {
                            val bad = unstable(copy, controller, tracker, trialSettings, journal) ?: return@TuneEngine done(TuneFind.Launches)
                            known = bad
                            if (bad.outcome.result == TrialResult.START_FAILED) return@TuneEngine done(TuneFind.Stop(TuneStatus.START_FAILED, bad.outcome.note))
                            if (bad.outcome.result == TrialResult.CANCELLED) return@TuneEngine done(TuneFind.Stop(TuneStatus.CANCELLED))
                            continue
                        }
                        TrialResult.START_FAILED -> return@TuneEngine done(TuneFind.Stop(TuneStatus.START_FAILED, known.outcome.note))
                        TrialResult.CANCELLED -> return@TuneEngine done(TuneFind.Stop(TuneStatus.CANCELLED))
                        else -> {}
                    }
                    val diag = known.diagnosis ?: break
                    learnCrash(diag, copy, known.key)
                    val index = ModIndex.load(copy.modsDir)
                    val direct = directCulprit(diag, index)
                    if (direct != null) {
                        if (diag.findings.any { it.cause == Cause.MIXIN_NO_REFMAP && it.jar == direct }) needRefmap += direct
                        learnBroken(diag, direct, copy)
                        fixPrints[direct] = dev.crashhunter.core.db.DbRefs.fingerprint(known.outcome.fingerprint)
                        return@TuneEngine done(TuneFind.Culprits(listOf(direct), known.outcome.fingerprint, direct = true))
                    }
                    nextDbCandidate(copy)?.let { c -> return@TuneEngine done(TuneFind.Culprits(c.files, known.outcome.fingerprint, direct = c.direct, removable = false)) }
                    val kinds = typedKinds(diag)
                    if (kinds.isEmpty()) break
                    val all = runCatching { planner?.invoke(kinds)?.plan(copy, index, listOf(diag)) }.getOrNull().orEmpty().filter { it.actions.isNotEmpty() }
                    val usable = all.mapNotNull { permitted(it, fileRules, copy) }.sortedBy { if (it.kind == FixKind.DB_FIX) 0 else 1 }
                    if (usable.isEmpty()) {
                        if (all.any { it.kind != FixKind.DB_FIX }) return@TuneEngine done(TuneFind.Stop(TuneStatus.BLOCKED, diag.title))
                        if (diag.cause in ENVIRONMENT) return@TuneEngine done(TuneFind.Stop(TuneStatus.ENVIRONMENT, diag.title))
                        break
                    }
                    var moved = false
                    for (p in usable.take(3)) {
                        progress(TuneProgress.Event(TuneEvent.TryFix(p.kind, p.target, p.params)))
                        recorder.notes += "try:${p.kind.name}"
                        val record = runCatching { workApplier.apply(copy, listOf(p)) }.getOrNull() ?: continue
                        val before = known
                        val after = launchAll(copy, controller, tracker, trialSettings, journal)
                        if (after.outcome.result == TrialResult.START_FAILED || after.outcome.result == TrialResult.CANCELLED) {
                            workApplier.undo(record)
                            last = before
                            return@TuneEngine done(TuneFind.Stop(if (after.outcome.result == TrialResult.CANCELLED) TuneStatus.CANCELLED else TuneStatus.START_FAILED, after.outcome.note ?: ""))
                        }
                        val resolved = after.outcome.result == TrialResult.FAIL && (findingKeys(before.diagnosis) - findingKeys(after.diagnosis)).isNotEmpty() &&
                            findingKeys(after.diagnosis).isNotEmpty()
                        val newFailure = resolved || after.outcome.result == TrialResult.FAIL && before.outcome.fingerprint != null && after.outcome.fingerprint != null &&
                            !StackTraceTools.sameFailure(before.outcome.fingerprint, after.outcome.fingerprint)
                        TuneEvent.FixResult(p.kind, p.target, after.outcome.result, newFailure).let { ev -> progress(TuneProgress.Event(ev)); record(ev, copy) }
                        if (after.outcome.result == TrialResult.PASS || newFailure) {
                            learnFix(p, record.steps, after.outcome.result == TrialResult.PASS, before.outcome.fingerprint)
                            fixes += Fixed(round, p.kind, p.target, p.params, p.actions, after.outcome.result == TrialResult.PASS)
                            if (p.kind in WIDE_KINDS) passSets.clear()
                            ModIndex.load(copy.modsDir).also { remember(it) }
                            known = after
                            moved = true
                            break
                        }
                        workApplier.undo(record)
                        last = before
                        known = before
                    }
                    if (!moved) {
                        if (diag.cause in ENVIRONMENT) return@TuneEngine done(TuneFind.Stop(TuneStatus.ENVIRONMENT, diag.title))
                        break
                    }
                }
                if (known.outcome.result == TrialResult.PASS) {
                    val bad = unstable(copy, controller, tracker, trialSettings, journal) ?: return@TuneEngine done(TuneFind.Launches)
                    if (bad.outcome.result != TrialResult.FAIL && bad.outcome.result != TrialResult.HANG) return@TuneEngine done(TuneFind.Stop(TuneStatus.INCONCLUSIVE, UNSTABLE))
                    known = bad
                }
                staticCulprit(known, ModIndex.load(copy.modsDir))?.let { files ->
                    triedStatic += files.sorted().joinToString("|")
                    progress(TuneProgress.Note("staticCulprit", listOf(files.joinToString(", ") { ModIndex.load(copy.modsDir).byFileName(it)?.displayName ?: it })))
                    return@TuneEngine done(TuneFind.Culprits(files, known.outcome.fingerprint, direct = true))
                }
                known.diagnosis?.let { d -> dependencyCulprit(d, ModIndex.load(copy.modsDir)) }?.let { f ->
                    triedDiagnosed += f
                    progress(TuneProgress.Note("dependency", listOf(ModIndex.load(copy.modsDir).byFileName(f)?.displayName ?: f)))
                    return@TuneEngine done(TuneFind.Culprits(listOf(f), known.outcome.fingerprint, direct = true))
                }
                known.diagnosis?.let { d -> diagnosedCulprit(d, ModIndex.load(copy.modsDir)) }?.let { f ->
                    triedDiagnosed += f
                    progress(TuneProgress.Note("diagnosed", listOf(ModIndex.load(copy.modsDir).byFileName(f)?.displayName ?: f)))
                    return@TuneEngine done(TuneFind.Culprits(listOf(f), known.outcome.fingerprint, direct = true, removable = false))
                }
                if (known.outcome.result == TrialResult.HANG && !LoadGuard.singleThreaded(copy) && LoadGuard.limitThreads(copy)) {
                    progress(TuneProgress.Note("threads"))
                    known = launchAll(copy, controller, tracker, trialSettings, journal)
                    when (known.outcome.result) {
                        TrialResult.PASS -> {
                            val bad = unstable(copy, controller, tracker, trialSettings, journal) ?: return@TuneEngine done(TuneFind.Launches)
                            if (bad.outcome.result != TrialResult.FAIL && bad.outcome.result != TrialResult.HANG) return@TuneEngine done(TuneFind.Stop(TuneStatus.INCONCLUSIVE, UNSTABLE))
                            known = bad
                        }
                        TrialResult.START_FAILED -> return@TuneEngine done(TuneFind.Stop(TuneStatus.START_FAILED, known.outcome.note))
                        TrialResult.CANCELLED -> return@TuneEngine done(TuneFind.Stop(TuneStatus.CANCELLED))
                        else -> {}
                    }
                }
                val keyNow = dev.crashhunter.core.bisect.BisectEngine.keyOf(enabledOf(copy))
                progress(TuneProgress.Phase("search"))
                val hang = known.outcome.result == TrialResult.HANG
                if (hang) progress(TuneProgress.Note("hangMode"))
                val total = enabledOf(copy).size
                val seedOutcome = known.outcome
                suspend fun search() = BisectSession(launcher, copy, bisectSettings.copy(timeoutSec = adaptive(trialSettings).timeoutSec, hangIsFailure = bisectSettings.hangIsFailure || hang), diagnosisService, known.diagnosis, extraSuspects = (dbSuspects(copy) + suspicion.top(enabledOf(copy))).distinct(), inPlace = true,
                    seed = mapOf(keyNow to seedOutcome), knownPass = passSets, recorder = recorder,
                    rank = { suspicion.rank(it) }, onVerdict = { set, v -> suspicion.trial(set, total, v) }, minRepeats = if (hang) 2 else 1) { p -> progress(TuneProgress.Bisect(p)) }.run()
                var report = search()
                var retries = 0
                while (report.result.status == BisectStatus.START_FAILED && report.result.message == dev.crashhunter.core.bisect.PRISM_UNRESPONSIVE && retries < PRISM_RETRIES) {
                    retries++
                    progress(TuneProgress.Note("prismRetry", listOf(retries.toString(), PRISM_RETRIES.toString())))
                    runCatching { controller.closeLaunchers(listOf(copy.id)) }
                    kotlinx.coroutines.delay(PRISM_WAIT_MS * retries)
                    report = search()
                }
                lastBisect = report.result.culprits
                if (report.result.status == BisectStatus.FOUND) learnConflict(report.result.culprits, report.crashDiagnosis?.fingerprint ?: known.outcome.fingerprint, report.result.trials, copy)
                done(when (report.result.status) {
                    BisectStatus.FOUND -> TuneFind.Culprits(report.result.culprits, report.crashDiagnosis?.fingerprint ?: known.outcome.fingerprint)
                    BisectStatus.NO_FAILURE_AT_BASELINE -> if (unstable(copy, controller, tracker, trialSettings, journal) == null) TuneFind.Launches else TuneFind.Stop(TuneStatus.INCONCLUSIVE, UNSTABLE)
                    BisectStatus.NOT_A_MOD -> TuneFind.Stop(TuneStatus.NOT_A_MOD)
                    BisectStatus.START_FAILED -> TuneFind.Stop(TuneStatus.START_FAILED, report.result.message)
                    BisectStatus.CANCELLED -> TuneFind.Stop(TuneStatus.CANCELLED)
                    BisectStatus.INCONCLUSIVE -> TuneFind.Stop(TuneStatus.INCONCLUSIVE, report.result.message)
                    BisectStatus.ERROR -> TuneFind.Stop(TuneStatus.ERROR, report.result.message)
                })
            },
            versions = { file ->
                val index = ModIndex.load(copy.modsDir)
                val jar = index.byFileName(file) ?: return@TuneEngine emptyList()
                val rule = fileRules[file]
                val list = if (rule != null && rule.hasRange) {
                    val all = resolver?.versionList(jar, copy)?.entries ?: emptyList()
                    inRange(all, rule).filter { !it.current }.map { it.remote }
                } else resolver?.forRepair(jar, copy)?.candidates ?: emptyList()
                val wanted = dbVersions(copy, index)[file].orEmpty()
                val usable = list.filter { !knownBroken(jar, it) }
                if (wanted.isEmpty()) usable else usable.sortedBy { c -> if (wanted.any { v -> c.versionLabel.contains(v) || c.fileName.contains(v) }) 0 else 1 }
            },
            replace = { file, remote ->
                val index = ModIndex.load(copy.modsDir)
                val jar = index.byFileName(file)
                jar?.let { fromRefs[file] = dev.crashhunter.core.db.DbRefs.of(it) }
                val p = FixProposal("TUNE:$file", FixKind.REPLACE_VERSION, jar?.displayName ?: file, mapOf("file" to file),
                    listOf(ReplaceMod(file, listOf(remote), jar?.primaryId, null, jar?.let { rangesRequiredBy(it, index, copy.profile.loader) } ?: emptyList(), file in needRefmap)))
                val record = workApplier.apply(copy, listOf(p))
                val added = record.steps.filterIsInstance<FileAdded>().lastOrNull()?.path?.let { File(it).name } ?: return@TuneEngine null
                lastAdded = ModIndex.load(copy.modsDir).byFileName(added)?.let { dev.crashhunter.core.db.DbRefs.of(it) }
                remember(ModIndex.load(copy.modsDir))
                fileRules[file]?.let { fileRules[added] = it }
                ReplaceDone(added) { workApplier.undo(record); remember(ModIndex.load(copy.modsDir)) }
            },
            launch = {
                launchAll(copy, controller, tracker, trialSettings, journal).outcome
            },
            dependents = { file ->
                val index = ModIndex.load(copy.modsDir)
                DependencyGraph(index, copy.profile.loader).withDependents(listOf(file)).filter { index.byFileName(it)?.enabled == true || it == file }.toSet()
            },
            disable = { files -> last = null; for (f in files) { val jar = File(copy.modsDir, f); if (jar.isFile) jar.renameTo(File(copy.modsDir, "$f.disabled")) }; remember(ModIndex.load(copy.modsDir)) },
            sameFailure = { a, b -> StackTraceTools.sameFailure(a, b) },
            listener = { e -> progress(TuneProgress.Event(e)); record(e, copy) },
            loader = loaderOps(copy),
            removeFirst = true,
            enable = { files -> last = null; for (f in files) { val off = File(copy.modsDir, "$f.disabled"); if (off.isFile) off.renameTo(File(copy.modsDir, f)) }; remember(ModIndex.load(copy.modsDir)) },
            resume = resumed?.let { resume!!.engine },
            checkpoint = { st -> runCatching { store?.save(TuneCheckpoint(source.id, copy.dir.absolutePath, System.currentTimeMillis(), st, passSets.map { it.sorted() }, staticDone)) } },
            stable = {
                val bad = unstable(copy, controller, tracker, trialSettings, journal)
                if (bad?.outcome?.result == TrialResult.CANCELLED) throw kotlinx.coroutines.CancellationException("cancelled")
                bad == null
            },
        )
        val raw = try {
            engine.run()
        } catch (e: kotlinx.coroutines.CancellationException) {
            TuneResult(TuneStatus.CANCELLED, emptyList())
        } catch (e: Exception) {
            TuneResult(TuneStatus.ERROR, emptyList(), message = e.message ?: e.javaClass.simpleName)
        }
        val result = if (raw.status == TuneStatus.LAUNCHED && !sourceSingle && LoadGuard.singleThreaded(copy))
            raw.copy(steps = raw.steps + Fixed(raw.steps.maxOfOrNull { it.round } ?: 1, FixKind.LOAD_THREADS, "fml.toml", emptyMap(), listOf(dev.crashhunter.core.fix.LimitThreads), true))
            else raw
        runCatching { controller.closeLaunchers(listOf(copy.id)) }
        runCatching { if (result.status in TuneResume.RESUMABLE) store?.mark(source.id, result.status, result.message) else store?.clear(source.id) }
        copyIndex = ModIndex.load(copy.modsDir)
        return TuneReport(result, source, copy, names, versions, learned(result))
    }

    private class Known(val key: String, val outcome: dev.crashhunter.core.bisect.TrialOutcome, val diagnosis: dev.crashhunter.core.analyze.Diagnosis?)

    private var last: Known? = null
    var lastBisect: List<String> = emptyList()
        private set
    var copyIndex: ModIndex? = null
        private set
    private var trying: String? = null

    private fun ref(copy: GameInstance, file: String) = ModIndex.load(copy.modsDir).byFileName(file)?.let { dev.crashhunter.core.db.DbRefs.of(it) }

    private fun record(e: TuneEvent, copy: GameInstance) {
        val r = recorder
        when (e) {
            is TuneEvent.Round -> r.rounds = e.number
            is TuneEvent.Found -> if (e.direct) r.direct++
            is TuneEvent.TryVersion -> trying = e.file
            is TuneEvent.TryRestore -> trying = e.file
            is TuneEvent.VersionResult -> if (e.result == TrialResult.FAIL && !e.newFailure) {
                val from = fromRefs[e.file]; val to = lastAdded
                if (from != null && to != null && learnedItems.size < 40) learnedItems += dev.crashhunter.core.db.FixItem("TUNE_VERSION", listOf(from), listOf(to), "failed", fixPrints[e.file])
            }
            else -> {}
        }
        when (e) {
            is TuneEvent.VersionResult -> r.steps += dev.crashhunter.core.db.RunStep(r.rounds, "version", trying?.let { ref(copy, it) }, null, e.label, e.result.name == "PASS")
            is TuneEvent.NoVersions -> r.steps += dev.crashhunter.core.db.RunStep(r.rounds, "noVersions", ref(copy, e.file))
            is TuneEvent.TryLoader -> {}
            is TuneEvent.LoaderResult -> r.steps += dev.crashhunter.core.db.RunStep(r.rounds, "loader", null, copy.profile.loaderVersion, e.version, e.result.name == "PASS")
            is TuneEvent.RemovedMods -> r.steps += dev.crashhunter.core.db.RunStep(r.rounds, "remove", ref(copy, e.file + ".disabled") ?: ref(copy, e.file), also = e.also.size)
            is TuneEvent.TryFix -> {}
            is TuneEvent.FixResult -> r.steps += dev.crashhunter.core.db.RunStep(r.rounds, "fix:${e.kind.name}", null, null, e.target, e.result.name == "PASS")
            is TuneEvent.Fixed -> {}
            is TuneEvent.RestoreResult -> r.steps += dev.crashhunter.core.db.RunStep(r.rounds, "restore", trying?.let { ref(copy, it) }, null, e.label, e.result.name == "PASS")
            else -> {}
        }
    }

    private var passMs = 0L

    private fun adaptive(base: TrialSettings): TrialSettings {
        val ms = passMs * 2
        if (ms <= 0) return base
        return base.copy(timeoutSec = (ms / 1000).toInt().coerceIn(MIN_TIMEOUT_SEC, base.timeoutSec))
    }
    private val needRefmap = HashSet<String>()

    private fun enabledOf(copy: GameInstance): List<String> = ModIndex.load(copy.modsDir).jars.filter { it.enabled }.map { it.fileName }

    private suspend fun launchAll(copy: GameInstance, controller: dev.crashhunter.core.launcher.GameLauncher, tracker: GameTracker, trialSettings: TrialSettings, journal: File): Known {
        val index = ModIndex.load(copy.modsDir)
        val enabled = index.jars.filter { it.enabled }.map { it.fileName }
        progress(TuneProgress.Launch(copy.id, enabled.size))
        var diag: dev.crashhunter.core.analyze.Diagnosis? = null
        val runner = PrismTrialRunner(
            controller, copy, ModToggler(copy.modsDir, journal), enabled, adaptive(trialSettings),
            fingerprintOf = { src -> diagnosisService.diagnose(src, copy, index).also { diag = it }.fingerprint },
            tracker = tracker,
            windowTitle = "CrashHunter - ",
            minimizeWindow = settings.minimizeGames,
            status = { st -> recorder.state(0, st); progress(TuneProgress.Bisect(BisectProgress.Log("state.$st"))) },
            dumpDir = File(dev.crashhunter.core.util.AppDirs.dataDir, "logs"),
        )
        val n = recorder.startLocal(0, "tune-launch", enabled.size)
        val started = System.currentTimeMillis()
        val o = runner.run(enabled.toSet()).also { journal.delete() }
        val took = System.currentTimeMillis() - started
        if (o.result == TrialResult.PASS) passMs = maxOf(passMs, took)
        if (o.result == TrialResult.FAIL) o.fingerprint?.let { fp ->
            if (namedPrints.add(fp.toString())) suspicion.named(fp.mods.mapNotNull { index.byModId(it)?.fileName }.distinct())
        }
        recorder.done(n, o.result.name, o.fingerprint)
        return Known(dev.crashhunter.core.bisect.BisectEngine.keyOf(enabled), o, diag).also { last = it }
    }

    private val learnedItems = mutableListOf<dev.crashhunter.core.db.DbItem>()
    private val fromRefs = HashMap<String, dev.crashhunter.core.db.ModRef>()
    private val fixPrints = HashMap<String, String?>()

    private fun known(copy: GameInstance, index: ModIndex) =
        runCatching { diagnosisService.knowledge?.invoke(index, copy.profile) }.getOrNull().orEmpty()

    private fun dbVersions(copy: GameInstance, index: ModIndex): Map<String, List<String>> =
        known(copy, index).filter { it.cause == Cause.KNOWN_FIX && it.jar != null && it.other != null }.groupBy({ it.jar!! }, { it.other!! })

    private class DbCandidate(val files: List<String>, val direct: Boolean)

    private val dbPlan = mutableListOf<DbCandidate>()
    private var dbCrashMods = emptyList<dev.crashhunter.core.db.ModRef>()
    private var dbUsed = 0

    private fun planFrom(check: dev.crashhunter.core.db.DbCheck, index: ModIndex): List<DbCandidate> {
        val enabled = index.jars.filter { it.enabled }
        val hashes = HashMap<String, String?>()
        fun fileOf(r: dev.crashhunter.core.db.ModRef) = enabled.firstOrNull { j -> dev.crashhunter.core.db.DbRefs.matches(r, j) { hashes.getOrPut(j.fileName) { dev.crashhunter.core.db.DbRefs.sha1(j.file) } } > 0 }?.fileName
        dbCrashMods = check.crashes.map { it.mod }
        val out = mutableListOf<DbCandidate>()
        dbPre.clear()
        for (b in check.known.broken) fileOf(b.mod)?.let { if (b.confirmed) dbPre += DbCandidate(listOf(it), true) else out += DbCandidate(listOf(it), true) }
        for (f in check.known.fixes) f.from.singleOrNull()?.let(::fileOf)?.let { out += DbCandidate(listOf(it), true) }
        for (c in check.known.conflicts) {
            val files = c.mods.map { fileOf(it) ?: return@map null }
            if (files.isNotEmpty() && files.all { it != null }) out += DbCandidate(files.filterNotNull(), files.size == 1)
        }
        return out.distinctBy { it.files.sorted() }.take(MAX_DB_CANDIDATES)
    }

    private val dbPre = mutableListOf<DbCandidate>()
    private var dbBrokenRefs = emptyList<dev.crashhunter.core.db.ModRef>()

    private fun nextDbBroken(copy: GameInstance): DbCandidate? {
        val enabled = ModIndex.load(copy.modsDir).jars.filter { it.enabled }.map { it.fileName }.toSet()
        while (dbPre.isNotEmpty()) {
            val c = dbPre.removeAt(0)
            if (c.files.all { it in enabled }) return c
        }
        return null
    }

    private fun knownBroken(jar: dev.crashhunter.core.mods.ModJar?, remote: dev.crashhunter.core.fix.RemoteFile): Boolean {
        val id = jar?.primaryId ?: return false
        return dbBrokenRefs.any { r -> r.id.equals(id, true) && r.v != null && (remote.fileName.contains(r.v) || remote.versionLabel.contains(r.v)) }
    }

    private fun nextDbCandidate(copy: GameInstance): DbCandidate? {
        val enabled = ModIndex.load(copy.modsDir).jars.filter { it.enabled }.map { it.fileName }.toSet()
        while (dbUsed < dbPlan.size) {
            val c = dbPlan[dbUsed++]
            if (c.files.all { it in enabled }) return c
        }
        return null
    }

    private fun dbSuspects(copy: GameInstance): List<String> {
        val index = ModIndex.load(copy.modsDir)
        val fromCrashes = index.jars.filter { j -> j.enabled && dbCrashMods.any { r -> dev.crashhunter.core.db.DbRefs.matches(r, j) { null } > 0 || j.mods.any { it.modId.equals(r.id, true) } } }.map { it.fileName }
        return (known(copy, index).filter { it.cause == Cause.KNOWN_CONFLICT || it.cause == Cause.KNOWN_BROKEN }.mapNotNull { it.jar } + fromCrashes).distinct()
    }

    private fun learnBroken(d: dev.crashhunter.core.analyze.Diagnosis, file: String, copy: GameInstance) {
        val f = d.findings.firstOrNull { it.jar == file && it.cause in DIRECT } ?: return
        val reason = when (f.cause) { Cause.MIXIN_NO_REFMAP -> "NO_REFMAP"; Cause.CORRUPT_JAR -> "BROKEN"; else -> return }
        val r = ref(copy, file) ?: return
        learnedItems += dev.crashhunter.core.db.BrokenItem(r, reason)
    }

    private fun learnConflict(files: List<String>, fp: dev.crashhunter.core.analyze.CrashFingerprint?, trials: Int, copy: GameInstance) {
        val refs = files.mapNotNull { ref(copy, it) ?: ref(copy, "$it.disabled") }
        if (refs.size != files.size || refs.isEmpty() || refs.size > 4) return
        learnedItems += dev.crashhunter.core.db.ConflictItem(refs, dev.crashhunter.core.db.DbRefs.fingerprint(fp), dev.crashhunter.core.db.DbRefs.exceptionName(fp), "bisect", trials)
        for (f in files) fixPrints[f] = dev.crashhunter.core.db.DbRefs.fingerprint(fp)
    }

    private fun learnFix(p: FixProposal, steps: List<dev.crashhunter.core.fix.DoneStep>, passed: Boolean, fp: dev.crashhunter.core.analyze.CrashFingerprint?) {
        if (p.kind !in LEARN_KINDS) return
        val from = mutableListOf<dev.crashhunter.core.db.ModRef>()
        val to = mutableListOf<dev.crashhunter.core.db.ModRef>()
        for (s in steps) when (s) {
            is dev.crashhunter.core.fix.FileMoved -> if (s.from.endsWith(".jar") || s.from.endsWith(".jar.disabled")) jarRef(File(s.backup))?.let(from::add)
            is FileAdded -> jarRef(File(s.path))?.let(to::add)
            else -> {}
        }
        if (from.isEmpty() || to.isEmpty()) return
        learnedItems += dev.crashhunter.core.db.FixItem(p.kind.name, from, to, if (passed) "fixed" else "changed", dev.crashhunter.core.db.DbRefs.fingerprint(fp))
    }

    private var lastAdded: dev.crashhunter.core.db.ModRef? = null
    private val crashPrints = HashSet<String>()

    private var baseKey: String? = null

    private fun learnCrash(d: dev.crashhunter.core.analyze.Diagnosis, copy: GameInstance, setKey: String) {
        if (crashPrints.size >= 3) return
        val key = dev.crashhunter.core.db.DbRefs.fingerprint(d.fingerprint) ?: d.cause.name
        if (!crashPrints.add(key)) return
        val context = if (setKey == baseKey) "launch" else "tune"
        runCatching { dev.crashhunter.core.db.DbDetails.crash(d, ModIndex.load(copy.modsDir), context) }.getOrNull()?.let { learnedItems += it }
    }

    private fun jarRef(f: File) = if (f.isFile) runCatching { dev.crashhunter.core.db.DbRefs.of(dev.crashhunter.core.mods.ModMetadataReader.read(f)) }.getOrNull() else null

    private fun learned(result: TuneResult): List<dev.crashhunter.core.db.DbItem> {
        val out = learnedItems.toMutableList()
        val idx = copyIndex
        if (result.status == TuneStatus.LAUNCHED && idx != null) runCatching { dev.crashhunter.core.db.DbDetails.compatible(idx) }.getOrNull()?.let { out += it }
        for (s in result.steps.filterIsInstance<Replaced>()) {
            val from = fromRefs[s.file] ?: continue
            val to = idx?.byFileName(s.newFile)?.let { dev.crashhunter.core.db.DbRefs.of(it) } ?: continue
            out += dev.crashhunter.core.db.FixItem("TUNE_VERSION", listOf(from), listOf(to), if (s.launched) "fixed" else "changed", s.culprits.firstNotNullOfOrNull { fixPrints[it] })
        }
        return out
    }

    private fun typedKinds(d: dev.crashhunter.core.analyze.Diagnosis): Set<FixKind> {
        val causes = setOf(d.cause) + d.findings.map { it.cause }
        val out = LinkedHashSet<FixKind>()
        out += FixKind.DB_FIX
        for (c in causes) out += TYPED[c].orEmpty()
        return out
    }

    private fun permitted(p: FixProposal, fileRules: Map<String, ModRule>, copy: GameInstance): FixProposal? {
        fun policy(f: String) = fileRules[f.removeSuffix(".disabled")]?.policy ?: ModPolicy.REMOVABLE
        val loaderRule = rules[LOADER_KEY] ?: ModRule(ModPolicy.KEEP)
        val actions = p.actions.map { a ->
            when (a) {
                is dev.crashhunter.core.fix.DisableMod -> if (policy(a.file) != ModPolicy.REMOVABLE) return null else a
                is ReplaceMod -> {
                    if (policy(a.file) == ModPolicy.PIN) return null
                    val allowed = fileRules[a.file]?.allowed?.toSet()
                    if (allowed == null) a else a.copy(candidates = a.candidates.filter { it.fileId in allowed }).takeIf { it.candidates.isNotEmpty() } ?: return null
                }
                is dev.crashhunter.core.fix.SetComponentVersion -> {
                    if (loaderRule.policy == ModPolicy.PIN) return null
                    if (loaderRule.allowed != null && a.version !in loaderRule.allowed) return null
                    a
                }
                else -> a
            }
        }
        return p.copy(actions = actions)
    }

    private var staticDone = false
    private val triedDiagnosed = HashSet<String>()
    private val passSets = mutableListOf<Set<String>>()

    private fun findingKeys(d: dev.crashhunter.core.analyze.Diagnosis?): Set<String> =
        d?.findings.orEmpty().filter { it.cause in DEPENDENCY_CAUSES }.map { "${it.cause}|${it.mod?.lowercase()}|${it.other?.lowercase()}" }.toSet()

    private fun dependencyCulprit(d: dev.crashhunter.core.analyze.Diagnosis, index: ModIndex): String? =
        d.findings.filter { it.cause in DEPENDENCY_CAUSES && it.mod != null }.mapNotNull { f ->
            (index.byModId(f.mod!!.lowercase()) ?: index.jars.firstOrNull { it.displayName.equals(f.mod, true) })?.takeIf { it.enabled }?.fileName
        }.firstOrNull { it !in triedDiagnosed }

    private fun diagnosedCulprit(d: dev.crashhunter.core.analyze.Diagnosis, index: ModIndex): String? =
        suspicion.rank(d.findings.filter { it.cause in DIAGNOSED && it.jar != null }.mapNotNull { f -> index.byFileName(f.jar!!)?.takeIf { it.enabled }?.fileName }.distinct())
            .firstOrNull { it !in triedDiagnosed }

    val suspicion = Suspicion()
    var staticFindings: List<dev.crashhunter.core.scan.StaticFinding> = emptyList()
        private set
    private val triedStatic = HashSet<String>()
    private val namedPrints = HashSet<String>()

    private fun prepareSuspicion(copy: GameInstance) {
        progress(TuneProgress.Phase("scan"))
        val index = ModIndex.load(copy.modsDir)
        staticFindings = runCatching { dev.crashhunter.core.scan.StaticScan(File(AppDirs.dataDir, "static-cache")).scan(index) }.getOrDefault(emptyList())
        suspicion.fromStatic(staticFindings)
        val enabled = index.jars.filter { it.enabled }.map { it.fileName }.toSet()
        val confirmed = dbPre.flatMap { it.files }
        val plan = dbPlan.flatMap { it.files }
        val known = known(copy, index)
        val conflicts = (plan + known.filter { it.cause == Cause.KNOWN_CONFLICT || it.cause == Cause.KNOWN_BROKEN }.mapNotNull { it.jar }).distinct()
        val fixes = known.filter { it.cause == Cause.KNOWN_FIX }.mapNotNull { it.jar }
        val crashes = dbSuspects(copy) - conflicts.toSet()
        suspicion.fromDb(confirmed.filter { it in enabled }, conflicts.filter { it in enabled }, fixes.filter { it in enabled }, crashes.filter { it in enabled })
        recorder.notes += "static:${staticFindings.size}"
        progress(TuneProgress.Note("scan", listOf(staticFindings.count { it.kind != dev.crashhunter.core.scan.StaticKind.DUPLICATE_CLASS }.toString())))
        val top = suspicion.top(enabled, limit = 5)
        if (top.isNotEmpty()) progress(TuneProgress.Note("suspects", listOf(top.joinToString(", ") { index.byFileName(it)?.displayName ?: it })))
    }

    private fun staticCulprit(known: Known, index: ModIndex): List<String>? {
        if (known.outcome.result != TrialResult.FAIL) return null
        val fp = known.outcome.fingerprint ?: return null
        val d = known.diagnosis
        val text = listOfNotNull(fp.exception, fp.message, d?.title, d?.detail, d?.parsed?.effectiveMessage).joinToString("\n") + "\n" + fp.frames.joinToString("\n")
        val named = (fp.mods.mapNotNull { index.byModId(it)?.fileName } + d?.suspects.orEmpty().take(3).mapNotNull { it.jar }).toSet()
        val mismatch = fp.exception == "cause:CLASS_MISMATCH" || MISMATCH_NAMES.any { fp.exception.endsWith(it) || text.contains(it) }
        val enabled = index.jars.filter { it.enabled }.map { it.fileName }.toSet()
        val hits = staticFindings.filter { f -> f.kind != dev.crashhunter.core.scan.StaticKind.DUPLICATE_CLASS && f.files.all { it in enabled } }.filter { f ->
            f.classes.any { c -> text.contains(c) || (c.length > 8 && text.contains(c.substringAfterLast('.'))) } || (mismatch && f.files.any { it in named })
        }
        for (f in hits.sortedByDescending { it.kind.weight }) {
            val files = suspicion.rank(f.files)
            if (files.sorted().joinToString("|") in triedStatic) continue
            return files
        }
        return null
    }

    private suspend fun unstable(copy: GameInstance, controller: dev.crashhunter.core.launcher.GameLauncher, tracker: GameTracker, trialSettings: TrialSettings, journal: File): Known? {
        for (i in 2..STABLE_RUNS) {
            progress(TuneProgress.Note("stable", listOf(i.toString(), STABLE_RUNS.toString())))
            val k = launchAll(copy, controller, tracker, trialSettings, journal)
            if (k.outcome.result != TrialResult.PASS) {
                progress(TuneProgress.Note("unstable", listOf(i.toString())))
                return k
            }
        }
        return null
    }

    private suspend fun staticFixes(round: Int, copy: GameInstance, fileRules: Map<String, ModRule>): List<TuneStep> {
        val plan = planner ?: return emptyList()
        val index = ModIndex.load(copy.modsDir)
        val proposals = runCatching { plan(STATIC_KINDS).plan(copy, index, emptyList()) }.getOrNull().orEmpty()
            .filter { it.actions.isNotEmpty() && it.params["db"] != "1" && it.params["auto"] != "0" }
            .mapNotNull { permitted(it, fileRules, copy) }
        val out = mutableListOf<TuneStep>()
        for (p in proposals) {
            val record = runCatching { workApplier.apply(copy, listOf(p)) }.getOrNull() ?: continue
            if (record.steps.isEmpty()) continue
            recorder.notes += "static:${p.kind.name}"
            progress(TuneProgress.Note("static", listOf(p.kind.name), p.params))
            out += Fixed(round, p.kind, p.target, p.params, p.actions, false)
        }
        return out
    }

    private fun directCulprit(d: dev.crashhunter.core.analyze.Diagnosis, index: ModIndex): String? {
        val f = d.findings.firstOrNull { it.cause in DIRECT && it.jar != null } ?: return null
        val jar = index.byFileName(f.jar!!) ?: return null
        return jar.fileName.takeIf { jar.enabled }
    }

    private fun loaderOps(copy: GameInstance): LoaderOps? {
        val uid = dev.crashhunter.core.fix.FixPlanner.loaderUid(copy.profile.loader) ?: return null
        val current = copy.profile.loaderVersion ?: return null
        val rule = rules[LOADER_KEY] ?: ModRule(ModPolicy.KEEP)
        return LoaderOps(uid, current, rule.policy,
            candidates = { closestFirst(resolver?.loaderVersions(copy.profile) ?: emptyList(), current, rule.allowed) },
            set = { v ->
                val p = FixProposal("TUNE:LOADER", FixKind.LOADER_UPDATE, uid, mapOf("to" to v), listOf(dev.crashhunter.core.fix.SetComponentVersion(uid, v)))
                val record = workApplier.apply(copy, listOf(p))
                passSets.clear()
                ReplaceDone(v) { workApplier.undo(record); passSets.clear() }
            })
    }

    companion object {
        const val MAX_DB_CANDIDATES = 3
        var STABLE_RUNS = 6
        const val UNSTABLE = "unstable"
        val MISMATCH_NAMES = listOf("IncompatibleClassChangeError", "NoSuchMethodError", "NoSuchFieldError", "AbstractMethodError", "NoClassDefFoundError", "VerifyError", "overrides final method")
        const val MIN_TIMEOUT_SEC = 180
        const val PRISM_RETRIES = 2
        var PRISM_WAIT_MS = 20_000L
        val DIRECT = setOf(Cause.MIXIN_NO_REFMAP, Cause.KNOWN_BROKEN, Cause.CORRUPT_JAR)
        val WIDE_KINDS = setOf(FixKind.LOADER_UPDATE, FixKind.MEMORY, FixKind.JAVA, FixKind.CONFIG_RESET)
        val DEPENDENCY_CAUSES = setOf(Cause.MISSING_DEPENDENCY, Cause.DEPENDENCY_VERSION)
        val DIAGNOSED = setOf(Cause.MIXIN_FAILURE, Cause.MOD_LOAD_ERROR, Cause.MOD_RUNTIME_ERROR, Cause.CLASS_MISMATCH)
        val STATIC_KINDS = setOf(FixKind.CRASH_TOOL, FixKind.DUPLICATE, FixKind.WRONG_LOADER, FixKind.MC_MISMATCH, FixKind.CORRUPT_JAR,
            FixKind.ENABLE_PROVIDER, FixKind.ADD_DEPENDENCY, FixKind.UPDATE_DEPENDENCY)
        val LEARN_KINDS = setOf(FixKind.DB_FIX, FixKind.REPLACE_BROKEN, FixKind.UPDATE_DEPENDENCY, FixKind.REPLACE_VERSION)
        val ENVIRONMENT = setOf(Cause.JAVA_VERSION, Cause.OUT_OF_MEMORY, Cause.GPU_DRIVER, Cause.NATIVE_CRASH, Cause.CONFIG_ERROR)
        val TYPED: Map<Cause, Set<FixKind>> = mapOf(
            Cause.MISSING_DEPENDENCY to setOf(FixKind.ENABLE_PROVIDER, FixKind.ADD_DEPENDENCY),
            Cause.DEPENDENCY_VERSION to setOf(FixKind.ENABLE_PROVIDER, FixKind.UPDATE_DEPENDENCY),
            Cause.CLASS_MISMATCH to setOf(FixKind.ENABLE_PROVIDER),
            Cause.LOADER_VERSION to setOf(FixKind.LOADER_UPDATE),
            Cause.MC_VERSION to setOf(FixKind.MC_MISMATCH),
            Cause.WRONG_LOADER to setOf(FixKind.WRONG_LOADER),
            Cause.DUPLICATE_MOD to setOf(FixKind.DUPLICATE),
            Cause.OUT_OF_MEMORY to setOf(FixKind.MEMORY),
            Cause.JAVA_VERSION to setOf(FixKind.JAVA),
            Cause.CONFIG_ERROR to setOf(FixKind.CONFIG_RESET),
        )

        fun proposals(result: TuneResult, names: Map<String, String>): List<FixProposal> {
            val renamed = HashMap<String, String>()
            fun original(f: String): String = renamed[f] ?: f
            val out = mutableListOf<FixProposal>()
            for (s in result.steps) when (s) {
                is Replaced -> {
                    val orig = original(s.file)
                    renamed[s.newFile] = orig
                    out += FixProposal("TUNE:REPLACE:$orig", FixKind.REPLACE_VERSION, names[s.file] ?: s.file,
                        mapOf("mod" to (names[s.file] ?: s.file), "file" to s.file, "new" to s.newFile),
                        listOf(ReplaceMod(s.file, listOf(s.remote))))
                }
                is Fixed -> out += FixProposal("TUNE:FIX:${s.kind}:${s.target}", s.kind, s.target, s.params, s.actions)
                is LoaderChanged -> out += FixProposal("TUNE:LOADER", FixKind.LOADER_UPDATE, s.uid, mapOf("loader" to s.uid, "from" to s.from, "to" to s.to),
                    listOf(dev.crashhunter.core.fix.SetComponentVersion(s.uid, s.to)))
                is Removed -> {
                    val files = listOf(s.file) + s.alsoRemoved
                    out += FixProposal("TUNE:REMOVE:${s.file}", FixKind.CULPRIT, names[s.file] ?: s.file,
                        mapOf("mod" to (names[s.file] ?: s.file), "file" to s.file),
                        files.map { DisableMod(it) })
                }
            }
            return out
        }
    }
}

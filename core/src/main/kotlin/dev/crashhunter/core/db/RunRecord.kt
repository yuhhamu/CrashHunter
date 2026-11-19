package dev.crashhunter.core.db

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.mods.ModIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RunTrial(
    val n: Int,
    val slot: Int,
    val phase: String,
    val enabled: Int,
    val pool: Int,
    val result: String? = null,
    val ms: Long? = null,
    val toRunningMs: Long? = null,
    val toMenuMs: Long? = null,
    val relaunches: Int = 0,
    val fp: String? = null,
    val ex: String? = null,
)

@Serializable
data class RunStep(val round: Int, val kind: String, val mod: ModRef? = null, val from: String? = null, val to: String? = null, val ok: Boolean? = null, val also: Int = 0)

@Serializable
data class RunReport(
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val schema: Int = 1,
    val install: String,
    val app: String,
    val time: Long,
    val kind: String,
    val env: DbEnv,
    val mods: List<ModRef>,
    val settings: Map<String, String>,
    val status: String,
    val durationMs: Long,
    val parallel: Int,
    val estimate: Int,
    val trials: List<RunTrial>,
    val culprits: List<ModRef> = emptyList(),
    val requirements: List<ModRef> = emptyList(),
    val suspects: List<ModRef> = emptyList(),
    val steps: List<RunStep> = emptyList(),
    val rounds: Int = 0,
    val direct: Int = 0,
    val notes: List<String> = emptyList(),
)

class RunRecorder(private val clock: () -> Long = System::currentTimeMillis) {
    private val started = clock()
    private val trials = LinkedHashMap<Int, RunTrial>()
    private val trialStart = HashMap<Int, Long>()
    private val slotTrial = HashMap<Int, Int>()
    private var nextLocal = 100_000
    val steps = mutableListOf<RunStep>()
    val notes = mutableListOf<String>()
    var parallel = 1
    var estimate = 0
    var rounds = 0
    var direct = 0
    val suspects = LinkedHashSet<String>()

    @Synchronized fun start(n: Int, slot: Int, phase: String, enabled: Int, pool: Int) {
        trials[n] = RunTrial(n, slot, phase, enabled, pool)
        trialStart[n] = clock()
        slotTrial[slot] = n
    }

    @Synchronized fun startLocal(slot: Int, phase: String, enabled: Int): Int {
        val n = nextLocal++
        start(n, slot, phase, enabled, enabled)
        return n
    }

    @Synchronized fun state(slot: Int, state: String) {
        val n = slotTrial[slot] ?: return
        val t = trials[n] ?: return
        val since = clock() - (trialStart[n] ?: return)
        trials[n] = when (state) {
            "running" -> if (t.toRunningMs == null) t.copy(toRunningMs = since) else t
            "menu" -> if (t.toMenuMs == null) t.copy(toMenuMs = since) else t
            "relaunch" -> t.copy(relaunches = t.relaunches + 1)
            else -> t
        }
    }

    @Synchronized fun done(n: Int, result: String, fp: CrashFingerprint?) {
        val t = trials[n] ?: return
        trials[n] = t.copy(result = result, ms = clock() - (trialStart[n] ?: clock()), fp = DbRefs.fingerprint(fp), ex = DbRefs.exceptionName(fp))
    }

    @Synchronized fun build(
        install: String, app: String, kind: String, env: DbEnv, index: ModIndex, settings: Map<String, String>, status: String,
        culprits: List<String> = emptyList(), requirements: List<String> = emptyList(),
    ): RunReport {
        val refs = HashMap<String, ModRef>()
        fun ref(file: String): ModRef? = refs.getOrPut(file) { index.byFileName(file)?.let { DbRefs.of(it) } ?: return null }
        val mods = index.jars.filter { it.enabled }.mapNotNull { ref(it.fileName) }
        return RunReport(
            install = install, app = app, time = clock(), kind = kind, env = env, mods = mods, settings = settings, status = status,
            durationMs = clock() - started, parallel = parallel, estimate = estimate, trials = trials.values.toList(),
            culprits = culprits.mapNotNull { ref(it) }, requirements = requirements.mapNotNull { ref(it) }, suspects = suspects.mapNotNull { ref(it) },
            steps = steps.toList(), rounds = rounds, direct = direct, notes = notes.toList(),
        )
    }

    companion object {
        private val json = Json { encodeDefaults = false; explicitNulls = false }
        private val pretty = Json { encodeDefaults = false; explicitNulls = false; prettyPrint = true }
        fun encode(r: RunReport): String = json.encodeToString(RunReport.serializer(), r)
        fun preview(r: RunReport): String = pretty.encodeToString(RunReport.serializer(), r)
    }
}

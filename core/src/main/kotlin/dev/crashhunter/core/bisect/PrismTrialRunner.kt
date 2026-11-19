package dev.crashhunter.core.bisect

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.LaunchCrashDetector
import dev.crashhunter.core.analyze.LatestLogParser
import dev.crashhunter.core.analyze.StackTraceTools
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.launcher.GameLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

enum class JudgeMode { AUTO, MANUAL }

@Serializable
data class TrialSettings(
    val mode: JudgeMode = JudgeMode.AUTO,
    val startTimeoutSec: Int = 180,
    val timeoutSec: Int = 600,
    val settleOverrideSec: Int? = null,
)

class MenuMarkers(val strong: List<Regex>, val weak: List<Regex>, val strongSettleSec: Int, val weakSettleSec: Int, val quietSec: Int = 0) {
    companion object {
        fun load(): MenuMarkers {
            val text = MenuMarkers::class.java.getResourceAsStream("/crashhunter/markers.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val o = Json.parseToJsonElement(text).jsonObject
            fun list(k: String) = o[k]!!.jsonArray.map { Regex(it.jsonPrimitive.content) }
            return MenuMarkers(list("strong"), list("weak"), o["strongSettleSec"]!!.jsonPrimitive.int, o["weakSettleSec"]!!.jsonPrimitive.int,
                o["quietSec"]?.jsonPrimitive?.int ?: 0)
        }
    }

    fun settleFor(log: String): Int? = when {
        strong.any { it.containsMatchIn(log) } -> strongSettleSec
        weak.any { it.containsMatchIn(log) } -> weakSettleSec
        else -> null
    }
}

class ManualGate {
    @Volatile var answer: Boolean? = null
    @Volatile var waiting: Boolean = false
}

class PrismTrialRunner(
    private val controller: GameLauncher,
    private val instance: GameInstance,
    private val toggler: ModToggler,
    private val candidates: List<String>,
    private val settings: TrialSettings,
    private val fingerprintOf: (CrashSource) -> CrashFingerprint?,
    private val markers: MenuMarkers = MenuMarkers.load(),
    private val gate: ManualGate = ManualGate(),
    private val status: (String) -> Unit = {},
    private val pollMs: Long = 1_500,
    private val pidCheckMs: Long = 3_000,
    private val afterStopMs: Long = 1_500,
    private val tracker: dev.crashhunter.core.launcher.GameTracker? = null,
    private val relaunchCheckMs: Long = 4_000,
    private val maxRelaunches: Int = 2,
    private val refreshAfterMs: Long = 30_000,
    private val windowTitle: String? = null,
    private val minimizeWindow: Boolean = false,
) : TrialRunner {
    override suspend fun run(enabled: Set<String>): TrialOutcome = withContext(Dispatchers.IO) {
        toggler.apply(candidates, enabled)
        val since = System.currentTimeMillis() - 2_000
        val id = instance.id
        gate.answer = null
        status("launch")
        var proc = runInterruptible { controller.launch(id) }
        var start = System.currentTimeMillis()
        var launcherMissing = 0
        var relaunches = 0
        var refreshed = false
        var gameSeenAt: Long? = null
        var markerAt: Long? = null
        var settle = 0
        var lastPidCheck = 0L
        var gameAlive = false
        var pids = emptyList<Long>()
        var quietCheck = false
        var logSize = -1L
        var logChangedAt = 0L
        val tail = LogTail(File(instance.gameDir, "logs/latest.log"))
        var arranged = windowTitle == null && !minimizeWindow
        var arrangeTries = 0
        try {
            while (true) {
                val now = System.currentTimeMillis()
                LaunchCrashDetector.detect(instance.gameDir, since)?.let { c ->
                    status("crash")
                    delay(pidCheckMs)
                    return@withContext TrialOutcome(TrialResult.FAIL, fingerprintOf(CrashSource(c.kind, c.file, c.file?.lastModified() ?: now)), c.reason, c.file?.name)
                }
                if (now - lastPidCheck >= pidCheckMs) {
                    lastPidCheck = now
                    pids = runInterruptible { controller.gamePids(id) }
                    gameAlive = pids.isNotEmpty()
                    if (gameAlive) tracker?.add(id, pids)
                    if (gameAlive && gameSeenAt == null) { gameSeenAt = now; status("running") }
                    if (!gameAlive && gameSeenAt == null && runInterruptible { controller.handoffFailed(id, proc) }) {
                        if (relaunches >= maxRelaunches) return@withContext TrialOutcome(TrialResult.START_FAILED, note = PRISM_UNRESPONSIVE)
                        relaunches++
                        launcherMissing = 0
                        val cleared = runInterruptible { controller.clearStaleLaunchers(id) }
                        status(if (cleared > 0) "handoff" else "prismBusy")
                        delay(5_000L * relaunches)
                        proc = runInterruptible { controller.launch(id) }
                        start = System.currentTimeMillis()
                    } else if (!gameAlive && gameSeenAt == null && !refreshed && now - start >= refreshAfterMs && proc?.isAlive == false) {
                        refreshed = true
                        status("rescan")
                        runInterruptible { controller.refreshInstances() }
                        delay(2_000)
                        proc = runInterruptible { controller.launch(id) }
                        start = System.currentTimeMillis()
                    } else if (!gameAlive && gameSeenAt == null && now - start >= relaunchCheckMs) {
                        val none = runInterruptible { controller.launcherProcesses() }.isEmpty()
                        launcherMissing = if (none) launcherMissing + 1 else 0
                        if (launcherMissing >= 2 && relaunches < maxRelaunches) {
                            relaunches++
                            launcherMissing = 0
                            status("relaunch")
                            proc = runInterruptible { controller.launch(id) }
                            start = System.currentTimeMillis()
                        }
                    }
                    if (gameAlive && !arranged && arrangeTries < 60) {
                        arrangeTries++
                        if (runInterruptible { controller.arrangeWindows(pids, windowTitle, minimizeWindow) } > 0) arranged = true
                    }
                    if (!gameAlive && gameSeenAt != null) {
                        delay(pidCheckMs)
                        LaunchCrashDetector.detect(instance.gameDir, since)?.let { c ->
                            return@withContext TrialOutcome(TrialResult.FAIL, fingerprintOf(CrashSource(c.kind, c.file, c.file?.lastModified() ?: now)), c.reason, c.file?.name)
                        }
                        return@withContext TrialOutcome(TrialResult.FAIL, exitFingerprint(since), "exited")
                    }
                }
                if (gameSeenAt == null && now - start > settings.startTimeoutSec * 1000L) {
                    return@withContext TrialOutcome(TrialResult.START_FAILED, note = "game did not start")
                }
                when (settings.mode) {
                    JudgeMode.AUTO -> if (gameSeenAt != null) {
                        if (markerAt == null) {
                            tail.next(since)?.let { log -> markers.settleFor(log)?.let {
                                markerAt = now; settle = settings.settleOverrideSec ?: it; status("menu")
                                quietCheck = settings.settleOverrideSec == null && markers.quietSec > 0 && it > markers.strongSettleSec
                                logChangedAt = now
                                if (windowTitle != null && pids.isNotEmpty()) runInterruptible { controller.arrangeWindows(pids, windowTitle, false) }
                            } }
                        } else if (gameAlive) {
                            val waited = now - markerAt!!
                            if (quietCheck) {
                                val size = File(instance.gameDir, "logs/latest.log").length()
                                if (size != logSize) { logSize = size; logChangedAt = now }
                            }
                            val quiet = quietCheck && waited >= markers.strongSettleSec * 1000L && now - logChangedAt >= markers.quietSec * 1000L
                            if (quiet || waited >= settle * 1000L) return@withContext TrialOutcome(TrialResult.PASS)
                        }
                    }
                    JudgeMode.MANUAL -> if (gameSeenAt != null) {
                        gate.waiting = true
                        when (gate.answer) {
                            true -> return@withContext TrialOutcome(TrialResult.FAIL, CrashFingerprint("manual"), "manual")
                            false -> return@withContext TrialOutcome(TrialResult.PASS, note = "manual")
                            null -> {}
                        }
                    }
                }
                val limit = settings.timeoutSec * 1000L + if (markerAt != null) 120_000L else 0L
                if (now - start > limit) return@withContext TrialOutcome(TrialResult.HANG, note = "timeout")
                delay(pollMs)
            }
            @Suppress("UNREACHABLE_CODE")
            TrialOutcome(TrialResult.CANCELLED)
        } finally {
            gate.waiting = false
            status("stop")
            withContext(kotlinx.coroutines.NonCancellable) {
                runInterruptible { controller.stop(id, proc) }
                if (runInterruptible { controller.gamePids(id) }.isEmpty()) tracker?.clear(id)
                delay(afterStopMs)
            }
        }
    }

    private fun readLog(since: Long): String? {
        val log = File(instance.gameDir, "logs/latest.log")
        if (!log.isFile || log.lastModified() < since) return null
        return LaunchCrashDetector.readHead(log)
    }

    private fun exitFingerprint(since: Long): CrashFingerprint {
        val log = readLog(since) ?: return CrashFingerprint("exit")
        return StackTraceTools.fingerprint(LatestLogParser.parse(log)) ?: CrashFingerprint("exit")
    }
}

class LogTail(private val file: File, private val maxRead: Int = 16 * 1024 * 1024) {
    private var pos = 0L
    private var carry = ""

    fun next(since: Long): String? {
        if (!file.isFile || file.lastModified() < since) return null
        val size = file.length()
        if (size < pos) { pos = 0; carry = "" }
        if (size == pos) return null
        val text = runCatching {
            java.io.RandomAccessFile(file, "r").use { f ->
                f.seek(pos)
                val n = minOf(size - pos, maxRead.toLong()).toInt()
                val buf = ByteArray(n).also { f.readFully(it) }
                pos += n
                buf.toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        val chunk = carry + text
        val cut = chunk.lastIndexOf('\n')
        carry = if (cut >= 0) chunk.substring(cut + 1).takeLast(4096) else chunk.takeLast(4096)
        return if (cut >= 0) chunk.substring(0, cut + 1) else null
    }
}

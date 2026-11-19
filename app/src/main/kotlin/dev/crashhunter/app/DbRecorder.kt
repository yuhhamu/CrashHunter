package dev.crashhunter.app

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.analyze.StackTraceTools
import dev.crashhunter.core.bisect.BisectReport
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.db.BrokenItem
import dev.crashhunter.core.db.CompatibleItem
import dev.crashhunter.core.db.ConflictItem
import dev.crashhunter.core.db.CrashItem
import dev.crashhunter.core.db.DbDetails
import dev.crashhunter.core.db.DbItem
import dev.crashhunter.core.db.DbRefs
import dev.crashhunter.core.db.DbReport
import dev.crashhunter.core.db.FixItem
import dev.crashhunter.core.db.ModRef
import dev.crashhunter.core.fix.FileAdded
import dev.crashhunter.core.fix.FileMoved
import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.fix.FixOutcome
import dev.crashhunter.core.fix.FixState
import dev.crashhunter.core.fix.Renamed
import dev.crashhunter.core.fix.ReplaceMod
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModMetadataReader
import java.awt.Component
import java.io.File
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class DbRecorder(private val ctx: AppContext) {
    private fun ref(f: File): ModRef? = if (f.isFile) runCatching { DbRefs.of(ModMetadataReader.read(f)) }.getOrNull() else null

    fun fromBisect(report: BisectReport, source: GameInstance): DbReport? {
        if (report.result.status != BisectStatus.FOUND || report.culprits.isEmpty()) return null
        val env = DbRefs.env(source.profile) ?: return null
        val fp = report.crashDiagnosis?.fingerprint
        val items = mutableListOf<DbItem>()
        if (report.culprits.size <= 4) items += ConflictItem(report.culprits.map { DbRefs.of(it) }, DbRefs.fingerprint(fp), DbRefs.exceptionName(fp), "bisect", report.result.trials)
        report.crashDiagnosis?.let { d -> runCatching { DbDetails.crash(d, ModIndex.load(source.modsDir), "launch") }.getOrNull()?.let(items::add) }
        items += runCatching { DbDetails.dependencies(report.culprits, report.requirements) }.getOrDefault(emptyList())
        if (items.isEmpty()) return null
        return DbReport(install = ctx.installId(), app = VERSION, time = System.currentTimeMillis(), env = env, items = items)
    }

    fun fromTune(report: dev.crashhunter.core.tune.TuneReport): DbReport? {
        if (report.learned.isEmpty()) return null
        val env = DbRefs.env(report.source.profile) ?: return null
        return DbReport(install = ctx.installId(), app = VERSION, time = System.currentTimeMillis(), env = env, items = report.learned)
    }

    fun fromIngame(instance: GameInstance, d: Diagnosis, index: ModIndex): DbReport? {
        if (instance.id.endsWith("-CrashHunter") || d.cause == Cause.NO_PROBLEM || d.parsed == null) return null
        val env = DbRefs.env(instance.profile) ?: return null
        val item = runCatching { DbDetails.crash(d, index, "ingame", seenCount(instance, d)) }.getOrNull() ?: return null
        return DbReport(install = ctx.installId(), app = VERSION, time = System.currentTimeMillis(), env = env, items = listOf(item))
    }

    private fun seenCount(instance: GameInstance, d: Diagnosis): Int? {
        val fp = d.fingerprint ?: return null
        val since = System.currentTimeMillis() - 30L * 24 * 3600_000L
        return ctx.diagnosis.sources(instance, 20).filter { it.kind == SourceKind.CRASH_REPORT && it.timeMs >= since }
            .count { src -> runCatching { ctx.diagnosis.parse(src)?.let { StackTraceTools.fingerprint(it, d.cause.name) } }.getOrNull()?.let { StackTraceTools.sameFailure(fp, it) } == true }
            .takeIf { it > 0 }
    }

    fun fromFix(instance: GameInstance, out: FixOutcome, diagnosis: Diagnosis? = null): DbReport? {
        val env = DbRefs.env(instance.profile) ?: return null
        val items = mutableListOf<DbItem>()
        diagnosis?.let { d -> runCatching { DbDetails.crash(d, ModIndex.load(instance.modsDir), if (d.source.kind == SourceKind.CRASH_REPORT) "ingame" else "launch") }.getOrNull()?.let(items::add) }
        if (out.records.any { it.state == FixState.VERIFIED }) runCatching { DbDetails.compatible(ModIndex.load(instance.modsDir)) }.getOrNull()?.let(items::add)
        for (rec in out.records.filter { it.state == FixState.VERIFIED || it.state == FixState.KEPT_NEW_FAILURE }) {
            val from = mutableListOf<ModRef>()
            val to = mutableListOf<ModRef>()
            for (s in rec.steps) when (s) {
                is Renamed -> if (s.to.endsWith(".disabled")) ref(File(s.to))?.let(from::add) else ref(File(s.to))?.let(to::add)
                is FileMoved -> if (s.from.endsWith(".jar") || s.from.endsWith(".jar.disabled")) ref(File(s.backup))?.let(from::add)
                is FileAdded -> ref(File(s.path))?.let(to::add)
                else -> {}
            }
            if (from.isEmpty() && to.isEmpty()) continue
            val result = if (rec.state == FixState.VERIFIED) "fixed" else "changed"
            val kinds = rec.proposals.map { it.kind.name }.distinct()
            items += FixItem(kinds.joinToString("+"), from, to, result)
            for (p in rec.proposals.filter { it.kind == FixKind.REPLACE_BROKEN }) {
                val a = p.actions.filterIsInstance<ReplaceMod>().firstOrNull() ?: continue
                val old = rec.steps.filterIsInstance<FileMoved>().firstOrNull { File(it.from).name == a.file || File(it.from).name == a.file + ".disabled" } ?: continue
                ref(File(old.backup))?.let { items += BrokenItem(it, if (a.needRefmap) "NO_REFMAP" else "BROKEN") }
            }
        }
        for (rec in out.records.filter { it.state == FixState.UNDONE }) {
            for (p in rec.proposals.filter { it.fromDb }) {
                val file = p.actions.firstNotNullOfOrNull { a ->
                    when (a) { is ReplaceMod -> a.file; is dev.crashhunter.core.fix.DisableMod -> a.file; else -> null }
                } ?: continue
                val from = ref(File(instance.modsDir, file)) ?: ref(File(instance.modsDir, "$file.disabled")) ?: continue
                val to = p.params["to"]?.let { listOf(ModRef(from.id, it)) } ?: emptyList()
                items += FixItem(p.kind.name, listOf(from), to, "failed")
            }
        }
        if (items.none { it !is CrashItem && it !is CompatibleItem }) return null
        return DbReport(install = ctx.installId(), app = VERSION, time = System.currentTimeMillis(), env = env, items = items)
    }

    fun submit(report: DbReport?, parent: Component?, interactive: Boolean = true) {
        report ?: return
        Thread {
            runCatching { ctx.crashDb.recordLocal(report) }
            if (!ctx.settings.dbSend) return@Thread
            if (ctx.settings.dbConfirmSend) {
                if (!interactive || parent == null) return@Thread
                var answer = -1
                SwingUtilities.invokeAndWait { answer = confirm(report, parent) }
                when (answer) {
                    0 -> {}
                    2 -> ctx.update { it.copy(dbConfirmSend = false) }
                    else -> return@Thread
                }
            }
            runCatching { ctx.crashDb.enqueue(report) }
            runCatching { kotlinx.coroutines.runBlocking { ctx.crashDb.flush() } }
        }.apply { isDaemon = true; name = "crashdb-submit" }.start()
    }

    fun submitRun(report: dev.crashhunter.core.db.RunReport?, parent: Component?) {
        report ?: return
        if (!ctx.settings.dbSendRuns) return
        Thread {
            if (ctx.settings.dbConfirmSend) {
                if (parent == null) return@Thread
                var answer = -1
                SwingUtilities.invokeAndWait {
                    val s = ctx.strings
                    val area = JTextArea(dev.crashhunter.core.db.RunRecorder.preview(report), 16, 60).apply { isEditable = false; lineWrap = false }
                    val options = arrayOf(s["db.confirm.send"], s["db.confirm.skip"], s["db.confirm.always"])
                    answer = JOptionPane.showOptionDialog(parent, arrayOf(s["db.run.confirm.text"], JScrollPane(area)), s["db.confirm.title"],
                        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[1])
                }
                when (answer) {
                    0 -> {}
                    2 -> ctx.update { it.copy(dbConfirmSend = false) }
                    else -> return@Thread
                }
            }
            runCatching { ctx.crashDb.enqueueRun(report) }
            runCatching { kotlinx.coroutines.runBlocking { ctx.crashDb.flushRuns() } }
        }.apply { isDaemon = true; name = "crashdb-run" }.start()
    }

    fun runSettings(): Map<String, String> = ctx.settings.let { st ->
        mapOf(
            "parallelTrials" to st.parallelTrials.toString(), "maxParallel" to st.maxParallel.toString(), "repeats" to st.repeats.toString(),
            "settleSec" to (st.settleSec?.toString() ?: "auto"), "startTimeoutSec" to st.startTimeoutSec.toString(), "timeoutSec" to st.timeoutSec.toString(),
            "anyCrash" to st.anyCrash.toString(), "hangIsFailure" to st.hangIsFailure.toString(), "minimizeGames" to st.minimizeGames.toString(),
            "workCopy" to st.useWorkCopy.toString(), "dbUse" to st.dbUse.toString(), "algorithm" to "prefix-1",
        )
    }

    fun flushLater() {
        if (!ctx.settings.dbSend && !ctx.settings.dbSendRuns) return
        Thread { runCatching { kotlinx.coroutines.runBlocking { if (ctx.settings.dbSend) ctx.crashDb.flush(); if (ctx.settings.dbSendRuns) ctx.crashDb.flushRuns() } } }.apply { isDaemon = true; name = "crashdb-flush" }.start()
    }

    private fun confirm(report: DbReport, parent: Component): Int {
        val s = ctx.strings
        val area = JTextArea(ctx.crashDb.preview(report), 16, 60).apply { isEditable = false; lineWrap = false }
        val options = arrayOf(s["db.confirm.send"], s["db.confirm.skip"], s["db.confirm.always"])
        return JOptionPane.showOptionDialog(parent, arrayOf(s["db.confirm.text"], JScrollPane(area)), s["db.confirm.title"],
            JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[1])
    }
}

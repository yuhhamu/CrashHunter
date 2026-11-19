package dev.crashhunter.app

import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.bisect.BisectProgress
import dev.crashhunter.core.bisect.BisectReport
import dev.crashhunter.core.bisect.BisectSession
import dev.crashhunter.core.bisect.ManualGate
import dev.crashhunter.core.bisect.WorkCopy
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.util.AppDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

class BisectPanel(private val ctx: AppContext, private val frame: MainFrame) : JPanel(BorderLayout()) {
    private val scope = MainScope()
    private var job: Job? = null
    private var diagnosis: Diagnosis? = null
    private var pickedDiagnosis = false
    private var gate: ManualGate? = null
    private val body = JPanel(BorderLayout()).apply { border = BorderFactory.createEmptyBorder(4, 16, 16, 16) }

    private val bar = JProgressBar()
    private val trialLabel = Ui.label("", 15f, bold = true)
    private val etaLabel = Ui.label("", muted = true)
    private val yesButton = Ui.button(null, "") { answer(true) }
    private val noButton = Ui.button(null, "") { answer(false) }
    private val manualRow = Ui.row(yesButton, noButton)
    private val logLines = mutableListOf<String>()
    private val logArea = Ui.wrapText("", mono = true).apply { lineWrap = false; rows = 12 }
    private var resultFor: String? = null
    private val durations = mutableListOf<Long>()
    private val slotBox = Ui.vbox()
    private val slotRows = HashMap<Int, Pair<JLabel, JLabel>>()
    private val slotTrial = HashMap<Int, BisectProgress.Trial>()
    private val slotStart = HashMap<Int, Long>()
    private val slotDone = HashMap<Int, String>()
    private var parallelCount = 1
    private var runStart = 0L
    private var doneCount = 0
    private var estimate = 0
    private var goal = ""
    private var stopping = false
    private val slowHint = SlowHint(ctx)
    private val ticker = javax.swing.Timer(1000) { refreshTimes(); if (runStart > 0) slowHint.tick(System.currentTimeMillis() - runStart) }
    private val details = InstanceDetailsView(ctx)

    init {
        add(body, BorderLayout.CENTER)
        frame.onInstanceChange { inst ->
            if (job != null) return@onInstanceChange
            if (resultFor != null && inst?.id == resultFor) return@onInstanceChange
            resultFor = null
            if (!pickedDiagnosis) diagnosis = null
            showSetup()
        }
        showSetup()
    }

    fun diagnosesLoaded() { if (job == null && resultFor == null && !pickedDiagnosis) showSetup() }

    fun useDiagnosis(d: Diagnosis?) {
        diagnosis = d
        pickedDiagnosis = d != null
        if (job == null) showSetup()
    }

    fun autoStart() {
        val inst = frame.selectedInstance ?: return
        frame.showPage("bisect")
        start(inst, diagnosis ?: frame.diagnosisPage.latestCrash)
    }

    private fun setBody(c: JComponent) {
        body.removeAll()
        body.add(JScrollPane(c).apply { border = null; horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
        body.revalidate(); body.repaint()
    }

    private fun showSetup() {
        resultFor = null
        val inst = frame.selectedInstance
        if (inst == null) { body.removeAll(); body.add(EmptyState.create(ctx, frame)); body.revalidate(); body.repaint(); return }
        val d = diagnosis ?: frame.diagnosisPage.latestCrash
        val s = ctx.strings
        val card = Ui.card()
        card.add(Ui.row(Ui.label(inst.name, 18f, bold = true), Ui.label(inst.profile.label, muted = true)))
        card.add(Ui.gap(4))
        val enabled = inst.modsDir.listFiles { f -> f.name.endsWith(".jar") }?.size ?: 0
        card.add(Ui.label(s.f("bisect.modCount", enabled), muted = true))
        card.add(Ui.gap(14))

        card.add(Ui.label(s["bisect.target"], 11f, muted = true))
        card.add(Ui.wrapText(d?.let { "${it.title}${it.topSuspect?.let { t -> " — ${t.name}" } ?: ""}" } ?: s["bisect.target.none"]))
        card.add(Ui.gap(14))

        val auto = JRadioButton(s["bisect.mode.auto"], !ctx.settings.manualJudge)
        val manual = JRadioButton(s["bisect.mode.manual"], ctx.settings.manualJudge)
        ButtonGroup().apply { add(auto); add(manual) }
        auto.addActionListener { ctx.update { it.copy(manualJudge = false) } }
        manual.addActionListener { ctx.update { it.copy(manualJudge = true) } }
        card.add(Ui.row(Ui.label(s["bisect.mode"], 11f, muted = true), Ui.help(s["bisect.mode.help"]), gap = 4))
        card.add(Ui.row(auto, manual, gap = 16))
        card.add(Ui.gap(8))
        val copy = JCheckBox(s["bisect.workCopy"], ctx.settings.useWorkCopy)
        copy.addActionListener { ctx.update { it.copy(useWorkCopy = copy.isSelected) } }
        card.add(Ui.row(copy, Ui.help(s["bisect.workCopy.help"]), gap = 4))
        card.add(Ui.gap(16))
        card.add(Ui.row(Ui.button(LineIcon.Kind.PLAY, s["action.start"]) { start(inst, d) }))
        setBody(JPanel(BorderLayout()).apply { isOpaque = false; add(card, BorderLayout.NORTH) })
    }

    private fun start(inst: GameInstance, d: Diagnosis?) {
        val launcher = ctx.launcher ?: return
        val install = launcher.installation
        if (install.exe == null) { JOptionPane.showMessageDialog(this, ctx.strings["error.noExe"]); frame.showPage("settings"); return }
        if (!ctx.settings.useWorkCopy) {
            val ok = JOptionPane.showConfirmDialog(this, ctx.strings["bisect.direct.confirm"], "CrashHunter", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
            if (ok != JOptionPane.OK_OPTION) return
        }
        val g = ManualGate()
        gate = g
        durations.clear()
        slotRows.clear(); slotTrial.clear(); slotStart.clear(); slotDone.clear()
        parallelCount = 1; doneCount = 0; estimate = 0; goal = ""; stopping = false
        runStart = System.currentTimeMillis()
        details.reset()
        logLines.clear()
        logArea.text = ""
        ctx.bisectRunning = true
        showProgress()
        val dbSuspects = if (ctx.settings.dbUse) runCatching {
            dev.crashhunter.core.db.CrashDb.suspects(ctx.crashDb.snapshot(inst.profile), dev.crashhunter.core.mods.ModIndex.load(inst.modsDir))
        }.getOrDefault(emptyList()) else emptyList()
        val recorder = dev.crashhunter.core.db.RunRecorder()
        val session = BisectSession(launcher, inst, ctx.settings, ctx.diagnosis, d, gate = g, extraSuspects = dbSuspects, recorder = recorder) { p -> SwingUtilities.invokeLater { onProgress(p) } }
        job = scope.launch {
            var done: BisectReport? = null
            try { withContext(Dispatchers.IO) { done = session.run() } } catch (_: CancellationException) {
            } finally { ctx.bisectRunning = false; ticker.stop() }
            job = null
            val report = done ?: run { gate = null; showSetup(); return@launch }
            gate = null
            ctx.bisectRunning = false
            showResult(report, inst)
            if (ctx.settings.dbSendRuns && !ctx.settings.manualJudge) runCatching {
                val env = dev.crashhunter.core.db.DbRefs.env(inst.profile)
                if (env != null) {
                    val idx = withContext(Dispatchers.IO) { dev.crashhunter.core.mods.ModIndex.load(report.target.modsDir) }
                    val run = withContext(Dispatchers.IO) {
                        recorder.build(ctx.installId(), VERSION, "bisect", env, idx, DbRecorder(ctx).runSettings(), report.result.status.name,
                            report.result.culprits, report.result.requirements)
                    }
                    DbRecorder(ctx).submitRun(run, this@BisectPanel)
                }
            }
            if (report.result.status == dev.crashhunter.core.bisect.BisectStatus.CANCELLED) return@launch
            Ui.toast(this@BisectPanel, ctx.strings["toast.bisectDone"])
            DbRecorder(ctx).submit(DbRecorder(ctx).fromBisect(report, inst), this@BisectPanel)
            TrayNotifier.notify("CrashHunter", "${inst.name}: ${ctx.strings["toast.bisectDone"]}") {
                (SwingUtilities.getWindowAncestor(this@BisectPanel) as? MainFrame)?.bringToFront()
            }
        }
    }

    private fun showProgress() {
        val s = ctx.strings
        val card = Ui.card()
        card.add(trialLabel.apply { text = s["bisect.preparing"]; alignmentX = Component.LEFT_ALIGNMENT })
        card.add(Ui.gap(10))
        bar.isIndeterminate = true
        bar.maximumSize = Dimension(Int.MAX_VALUE, 10)
        bar.alignmentX = Component.LEFT_ALIGNMENT
        card.add(bar)
        card.add(Ui.gap(8))
        card.add(Ui.row(etaLabel.apply { text = "" }))
        slowHint.reset()
        card.add(slowHint.panel)
        card.add(Ui.gap(6))
        slotBox.removeAll()
        slotBox.alignmentX = Component.LEFT_ALIGNMENT
        card.add(slotBox)
        card.add(Ui.gap(12))
        yesButton.text = s["bisect.manual.yes"]
        noButton.text = s["bisect.manual.no"]
        manualRow.isVisible = false
        card.add(Ui.label(s["bisect.manual.question"], muted = true).also { it.isVisible = false; manualRow.putClientProperty("q", it) })
        card.add(manualRow)
        card.add(Ui.gap(12))
        card.add(Ui.row(Ui.button(LineIcon.Kind.STOP, s["action.cancel"]) { job?.cancel(); stopping = true; trialLabel.text = s["bisect.state.stopping"]; refreshSlots() }))
        card.add(Ui.gap(14))
        card.add(logSection(expanded = true))
        card.add(Ui.gap(10))
        card.add(Ui.collapsible(ctx.strings["details.title"], details.panel, true).apply { alignmentX = Component.LEFT_ALIGNMENT })
        setBody(JPanel(BorderLayout()).apply { isOpaque = false; add(card, BorderLayout.NORTH) })
        ticker.start()
    }

    private fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun buildSlots(n: Int) {
        parallelCount = n
        slotBox.removeAll()
        slotRows.clear()
        for (i in 0 until n) {
            val dot = JLabel(DotIcon(StatusColors.neutral))
            val text = Ui.label("", muted = true)
            slotRows[i] = dot to text
            slotBox.add(Ui.row(dot, text, gap = 6))
        }
        slotBox.revalidate(); slotBox.repaint()
        refreshSlots()
    }

    private fun refreshSlots() {
        val s = ctx.strings
        val now = System.currentTimeMillis()
        for ((i, row) in slotRows) {
            val t = slotTrial[i]
            val tag = if (parallelCount > 1) "#${i + 1}  " else ""
            val (dot, label) = row
            if (t == null) { label.text = tag + s["bisect.slot.idle"]; dot.icon = DotIcon(StatusColors.neutral); continue }
            val done = slotDone[i]
            val state = when {
                stopping && done == null -> s["bisect.state.stop"]
                done != null -> s["bisect.result.$done"]
                t.state.isNotEmpty() -> s["bisect.state.${t.state}"]
                else -> s["bisect.state.launch"]
            }
            val phase = s["log.phase.${t.phase}"]
            val since = slotStart[i]?.let { if (done == null) "  ·  " + clock(now - it) else "" } ?: ""
            label.text = "$tag$phase  ·  $state  ·  " + s.f("bisect.enabled", t.enabledCount, t.total) + since
            dot.icon = DotIcon(when {
                done == "PASS" -> StatusColors.ok
                done != null || t.state == "crash" -> StatusColors.error
                stopping -> StatusColors.warn
                else -> StatusColors.running
            })
        }
    }

    private fun refreshTimes() {
        val s = ctx.strings
        if (runStart == 0L) return
        val parts = mutableListOf(s.f("bisect.elapsed", clock(System.currentTimeMillis() - runStart)))
        if (estimate > 0 && !stopping) {
            val left = maxOf(estimate - doneCount, 1)
            val rounds = (left + parallelCount - 1) / parallelCount
            val avg = if (durations.isEmpty()) 90_000L else durations.average().toLong()
            parts += s.f("bisect.eta", (rounds * avg / 60_000).coerceAtLeast(1))
            parts += s.f("bisect.trial", doneCount, maxOf(estimate, doneCount))
        }
        etaLabel.text = parts.joinToString("  ·  ")
        refreshSlots()
    }

    private fun goalText(t: BisectProgress.Trial): String {
        val s = ctx.strings
        return when (t.phase) {
            "search", "suspect", "confirm" -> s.f("bisect.goal.${t.phase}", t.pool)
            else -> s["bisect.goal.${t.phase}"]
        }
    }

    private fun onProgress(p: BisectProgress) {
        val s = ctx.strings
        when (p) {
            is BisectProgress.Preparing -> {
                val step = p.step.substringBefore(':')
                val n = p.step.substringAfter(':', "")
                trialLabel.text = if (step == "restore" && parallelCount > 1 && n.isNotEmpty()) s.f("bisect.prep.restoreN", n) else s["bisect.prep.$step"]
                bar.isIndeterminate = true
            }
            is BisectProgress.Parallel -> { buildSlots(p.count); details.slots(p.instances) }
            is BisectProgress.Mods -> details.mods(p)
            is BisectProgress.Trial -> {
                details.trial(p)
                if (slotRows.isEmpty()) buildSlots(1)
                if (p.state.isEmpty()) {
                    slotStart[p.slot] = System.currentTimeMillis()
                    slotDone.remove(p.slot)
                    estimate = maxOf(estimate, p.estimate)
                    goal = goalText(p)
                }
                slotTrial[p.slot] = p
                if (!stopping) {
                    trialLabel.text = goal
                    bar.isIndeterminate = false
                    bar.maximum = maxOf(estimate, doneCount + 1)
                    bar.value = doneCount
                }
                manualRow.isVisible = false
                (manualRow.getClientProperty("q") as? JLabel)?.isVisible = false
                refreshTimes()
            }
            is BisectProgress.TrialDone -> {
                slotStart[p.slot]?.let { durations += System.currentTimeMillis() - it }
                slotDone[p.slot] = p.result.name
                doneCount++
                bar.maximum = maxOf(estimate, doneCount)
                bar.value = doneCount
                refreshTimes()
            }
            is BisectProgress.Log -> addLog(p)
            BisectProgress.WaitingForUser -> {
                manualRow.isVisible = true
                (manualRow.getClientProperty("q") as? JLabel)?.isVisible = true
            }
        }
    }

    private fun logSection(expanded: Boolean): JComponent {
        val scroll = JScrollPane(logArea).apply {
            preferredSize = Dimension(400, 230)
            maximumSize = Dimension(Int.MAX_VALUE, 230)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val content = Ui.vbox().apply {
            add(scroll)
            add(Ui.gap(6))
            add(Ui.row(Ui.iconButton(LineIcon.Kind.FOLDER, ctx.strings["bisect.log.open"]) { Ui.openFolder(AppDirs.logDir) }))
        }
        return Ui.collapsible(ctx.strings["bisect.log"], content, expanded).apply { alignmentX = Component.LEFT_ALIGNMENT }
    }

    private fun addLog(l: BisectProgress.Log) {
        val tag = l.slot?.let { "#${it + 1}  " } ?: ""
        val line = "${java.time.LocalTime.now().withNano(0)}  $tag${formatLog(l)}"
        logLines += line
        if (logArea.text.isEmpty()) logArea.text = line else logArea.append("\n" + line)
        logArea.caretPosition = logArea.document.length
    }

    private fun formatLog(l: BisectProgress.Log): String {
        val s = ctx.strings
        fun tr(prefix: String, v: String) = s["$prefix$v"].let { if (it == "$prefix$v") v else it }
        val a = l.args.toMutableList()
        when {
            l.key == "start" && a.size >= 3 -> { a[1] = tr("log.judge.", a[1]); if (a[2].isEmpty()) a[2] = "-" }
            l.key == "trial" && a.size >= 5 -> {
                a[1] = tr("log.phase.", a[1])
                a[4] = when {
                    a[4].startsWith("on: ") -> s.f("log.on", a[4].removePrefix("on: "))
                    a[4].startsWith("off: ") -> s.f("log.off", a[4].removePrefix("off: "))
                    else -> a[4]
                }
            }
            l.key.startsWith("done.") && a.size >= 2 -> a[1] = tr("log.note.", a[1]).let { if (it.isBlank()) "" else "— $it" }
        }
        val fmt = s["log.${l.key}"]
        if (fmt == "log.${l.key}") return (listOf(l.key) + a).joinToString(" ")
        return runCatching { String.format(fmt, *a.toTypedArray()) }.getOrElse { (listOf(fmt) + a).joinToString(" ") }.trimEnd()
    }

    private fun answer(reproduced: Boolean) {
        gate?.answer = reproduced
        manualRow.isVisible = false
        (manualRow.getClientProperty("q") as? JLabel)?.isVisible = false
    }

    private fun showResult(r: BisectReport, inst: GameInstance) {
        val del: (() -> Unit)? = if (r.workCopy && WorkCopy.isWorkCopy(r.target.dir)) ({
            val ok = JOptionPane.showConfirmDialog(this, ctx.strings["bisect.deleteCopy.confirm"], "CrashHunter", JOptionPane.OK_CANCEL_OPTION)
            if (ok == JOptionPane.OK_OPTION) {
                scope.launch { withContext(Dispatchers.IO) { WorkCopy.delete(r.target.dir) }; Ui.toast(this@BisectPanel, ctx.strings["toast.deleted"]); showResult(r.copy(workCopy = false), inst) }
            }
        }) else null
        resultFor = inst.id
        val page = Ui.vbox()
        page.add(ResultCards.bisect(ctx, r, del) { showSetup() }.apply { alignmentX = Component.LEFT_ALIGNMENT })
        if (r.result.status == dev.crashhunter.core.bisect.BisectStatus.FOUND) {
            page.add(Ui.gap(12))
            page.add(FixCard(ctx, inst, listOfNotNull(r.crashDiagnosis), r.culprits.map { it.fileName }, r.crashDiagnosis?.fingerprint).apply { alignmentX = Component.LEFT_ALIGNMENT })
        }
        if (logLines.isNotEmpty()) {
            page.add(Ui.gap(12))
            page.add(Ui.card().apply { add(logSection(expanded = false)) })
        }
        setBody(JPanel(BorderLayout()).apply { isOpaque = false; add(page, BorderLayout.NORTH) })
    }
}

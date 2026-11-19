package dev.crashhunter.app

import dev.crashhunter.core.bisect.BisectProgress
import dev.crashhunter.core.bisect.WorkCopy
import dev.crashhunter.core.fix.FixRecord
import dev.crashhunter.core.fix.VersionEntry
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar
import dev.crashhunter.core.tune.ModPolicy
import dev.crashhunter.core.tune.ModRule
import dev.crashhunter.core.tune.Removed
import dev.crashhunter.core.tune.Replaced
import dev.crashhunter.core.tune.TuneEvent
import dev.crashhunter.core.tune.TunePolicyStore
import dev.crashhunter.core.tune.TuneProgress
import dev.crashhunter.core.tune.TuneReport
import dev.crashhunter.core.tune.TuneSession
import dev.crashhunter.core.tune.TuneStatus
import dev.crashhunter.core.tune.modKey
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
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

class TunePanel(private val ctx: AppContext, private val frame: MainFrame) : JPanel(BorderLayout()) {
    private val scope = MainScope()
    private var job: Job? = null
    private val body = JPanel(BorderLayout()).apply { border = BorderFactory.createEmptyBorder(4, 16, 16, 16) }
    private val goalLabel = Ui.label("", 15f, bold = true)
    private val timeLabel = Ui.label("", muted = true)
    private val bar = JProgressBar().apply { isIndeterminate = true; maximumSize = Dimension(Int.MAX_VALUE, 10); alignmentX = Component.LEFT_ALIGNMENT }
    private val logArea = Ui.wrapText("", mono = true).apply {
        lineWrap = true; wrapStyleWord = true; rows = 14
        (caret as? javax.swing.text.DefaultCaret)?.updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
    }
    private val logScroll = JScrollPane(logArea).apply {
        preferredSize = Dimension(400, 260); maximumSize = Dimension(Int.MAX_VALUE, 260); alignmentX = Component.LEFT_ALIGNMENT
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private var unseen = 0
    private val latestButton = Ui.button(null, "") { toBottom() }.apply { isVisible = false }
    private val status = TuneStatusView(ctx)
    private val liveNames = HashMap<String, String>()
    private val logLines = mutableListOf<String>()
    private var round = 0
    private var runStart = 0L
    private var multi = false
    private var report: TuneReport? = null
    private var applied: FixRecord? = null
    private val details = InstanceDetailsView(ctx)
    private val changes = mutableListOf<String>()
    private var tryingFile: String? = null
    private val slowHint = SlowHint(ctx)
    private val ticker = javax.swing.Timer(1000) {
        timeLabel.text = ctx.strings.f("bisect.elapsed", clock(System.currentTimeMillis() - runStart))
        slowHint.tick(System.currentTimeMillis() - runStart)
    }

    init {
        add(body, BorderLayout.CENTER)
        frame.onInstanceChange { if (job == null && report == null) showSetup() }
        showSetup()
    }

    private fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun setBody(c: JComponent) {
        body.removeAll()
        body.add(JScrollPane(c).apply { border = null; horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
        body.revalidate(); body.repaint()
    }

    private data class Row(val key: String, val name: String, val jar: ModJar?)

    private inner class RuleModel(val inst: GameInstance, val rows: List<Row>) : AbstractTableModel() {
        val rules = HashMap(TunePolicyStore.load(inst.id))
        private val s = ctx.strings
        val policies = listOf(ModPolicy.REMOVABLE, ModPolicy.KEEP, ModPolicy.PIN)
        fun policiesFor(row: Int) = if (isLoader(row)) listOf(ModPolicy.KEEP, ModPolicy.PIN) else policies
        fun policyText(p: ModPolicy) = s["tune.policy.${p.name}"]
        fun isLoader(row: Int) = rows[row].jar == null
        fun rule(row: Int) = rules[rows[row].key] ?: if (isLoader(row)) ModRule(ModPolicy.KEEP) else ModRule()
        fun set(row: Int, r: ModRule) { rules[rows[row].key] = r; TunePolicyStore.save(inst.id, rules); fireTableRowsUpdated(row, row) }
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(c: Int) = s["tune.col.$c"]
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getValueAt(r: Int, c: Int): Any = when (c) {
            0 -> rows[r].name
            1 -> rule(r).policy
            else -> rule(r).let { ru ->
                when {
                    ru.policy == ModPolicy.PIN -> s["tune.range.pinned"]
                    ru.allowed != null -> (if (ru.allowedLabels.size in 1..3) ru.allowedLabels.joinToString(", ") else s.f("tune.range.some", ru.allowed?.size ?: 0)) + "  ▾"
                    else -> s["tune.range.any"] + "  ▾"
                }
            }
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            if (c != 1) return
            var p = policies.firstOrNull { policyText(it) == v } ?: return
            if (isLoader(r) && p == ModPolicy.REMOVABLE) p = ModPolicy.KEEP
            set(r, rule(r).copy(policy = p))
        }
    }

    private inner class SegmentRenderer(val model: RuleModel) : javax.swing.table.TableCellRenderer {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            val panel = JPanel(java.awt.GridLayout(1, 0, 2, 0)).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(2, 4, 2, 4) }
            val current = value as? ModPolicy
            for (p in model.policiesFor(table.convertRowIndexToModel(row))) panel.add(javax.swing.JToggleButton(model.policyText(p), p == current).apply {
                isFocusable = false
                margin = java.awt.Insets(0, 4, 0, 4)
                font = font.deriveFont(11f)
            })
            return panel
        }
    }

    private fun showSetup() {
        report = null
        applied = null
        val inst = frame.selectedInstance
        if (inst == null) { body.removeAll(); body.add(EmptyState.create(ctx, frame)); body.revalidate(); body.repaint(); return }
        val s = ctx.strings
        val card = Ui.card()
        card.add(Ui.row(Ui.label(inst.name, 18f, bold = true), Ui.label(inst.profile.label, muted = true), Ui.help(s["tune.help"]), gap = 8))
        card.add(Ui.gap(12))
        val holder = Ui.vbox().apply { alignmentX = Component.LEFT_ALIGNMENT }
        holder.add(Ui.loading(s["tune.loading"]))
        card.add(holder)
        setBody(JPanel(BorderLayout()).apply { isOpaque = false; add(card, BorderLayout.NORTH) })
        scope.launch {
            val jars = withContext(Dispatchers.IO) { ModIndex.load(inst.modsDir).jars.filter { it.enabled }.sortedBy { it.displayName.lowercase() } }
            if (frame.selectedInstance?.id != inst.id || job != null || report != null) return@launch
            val rows = mutableListOf<Row>()
            val lv = inst.profile.loaderVersion
            if (inst.profile.loader != dev.crashhunter.core.model.Loader.VANILLA && lv != null)
                rows += Row(dev.crashhunter.core.tune.LOADER_KEY, s.f("tune.loader.row", inst.profile.loader.display, lv), null)
            jars.forEach { rows += Row(modKey(it), it.displayName, it) }
            holder.removeAll()
            fillTable(holder, inst, RuleModel(inst, rows))
            holder.revalidate(); holder.repaint()
        }
    }

    private fun fillTable(holder: JComponent, inst: GameInstance, model: RuleModel) {
        val s = ctx.strings
        val table = JTable(model).apply {
            rowHeight = 28
            fillsViewportHeight = true
            dragEnabled = false
            tableHeader.reorderingAllowed = false
            setRowSelectionAllowed(false)
            columnModel.getColumn(1).cellRenderer = SegmentRenderer(model)
            columnModel.getColumn(0).preferredWidth = 300
            columnModel.getColumn(1).preferredWidth = 190
            columnModel.getColumn(2).preferredWidth = 200
            toolTipText = s["tune.range.tip"]
        }
        val sorter = javax.swing.table.TableRowSorter(model).apply { for (i in 0 until model.columnCount) setSortable(i, false) }
        table.rowSorter = sorter
        val search = javax.swing.JTextField().apply {
            putClientProperty("JTextField.placeholderText", s["tune.search"])
            putClientProperty("JTextField.showClearButton", true)
            maximumSize = Dimension(320, preferredSize.height)
            alignmentX = Component.LEFT_ALIGNMENT
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                fun apply() {
                    val q = text.trim()
                    sorter.rowFilter = if (q.isEmpty()) null else javax.swing.RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q), 0)
                }
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = apply()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = apply()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = apply()
            })
        }
        holder.add(search)
        holder.add(Ui.gap(6))
        var anchorRow = -1
        var dragPolicy: ModPolicy? = null
        val mouse = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                val viewRow = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (viewRow < 0) return
                val row = table.convertRowIndexToModel(viewRow)
                if (col == 1) {
                    val rect = table.getCellRect(viewRow, col, false)
                    val opts = model.policiesFor(row)
                    val idx = ((e.x - rect.x) * opts.size / maxOf(1, rect.width)).coerceIn(0, opts.size - 1)
                    val p = opts[idx]
                    val rows = if (e.isShiftDown && anchorRow >= 0) (minOf(anchorRow, viewRow)..maxOf(anchorRow, viewRow)).map { table.convertRowIndexToModel(it) } else listOf(row)
                    for (r in rows) if (p in model.policiesFor(r)) model.set(r, model.rule(r).copy(policy = p))
                    anchorRow = viewRow
                    dragPolicy = p
                } else if (col == 2 && e.clickCount == 1 && model.rule(row).policy != ModPolicy.PIN) editRange(inst, model, row)
            }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                val p = dragPolicy ?: return
                val viewRow = table.rowAtPoint(e.point)
                if (viewRow < 0) return
                val row = table.convertRowIndexToModel(viewRow)
                if (model.rule(row).policy == p || p !in model.policiesFor(row)) return
                model.set(row, model.rule(row).copy(policy = p))
            }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { dragPolicy = null }
        }
        table.addMouseListener(mouse)
        table.addMouseMotionListener(mouse)
        holder.add(JScrollPane(table).apply {
            preferredSize = Dimension(600, 360)
            maximumSize = Dimension(Int.MAX_VALUE, 360)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        holder.add(Ui.gap(14))
        holder.add(Ui.row(Ui.button(LineIcon.Kind.PLAY, s["action.start"]) { start(inst, model.rules.toMap()) }))
    }

    private fun editRange(inst: GameInstance, model: RuleModel, row: Int) {
        val s = ctx.strings
        val r = model.rows[row]
        val owner = SwingUtilities.getWindowAncestor(this)
        val dlg = JDialog(owner, s.f("tune.range.title", r.name), java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        val content = Ui.vbox(16)
        content.add(Ui.loading(s["tune.range.loading"]))
        dlg.contentPane = content
        dlg.size = Dimension(520, 200)
        dlg.setLocationRelativeTo(owner)
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    if (r.jar == null) {
                        val all = ctx.resolver().loaderVersions(inst.profile)
                        dev.crashhunter.core.fix.VersionList(all.map { v ->
                            VersionEntry(dev.crashhunter.core.fix.RemoteFile(dev.crashhunter.core.fix.RemoteSource.MODRINTH, v, null, null, null, "loader", v, v), "", v == inst.profile.loaderVersion)
                        }, reason = if (all.isEmpty()) "none" else null)
                    } else ctx.resolver().versionList(r.jar, inst)
                }.getOrNull()
            }
            content.removeAll()
            val entries = fetched?.entries.orEmpty()
            if (entries.isEmpty()) {
                val key = "tune.range.err.${fetched?.reason ?: "none"}"
                content.add(Ui.wrapText(if (s.has(key)) s[key] else s["tune.range.err.none"]))
                content.revalidate(); content.repaint()
                return@launch
            }
            val rule = model.rule(row)
            val initial = rule.allowed?.toSet()
            val on = BooleanArray(entries.size) { i -> initial == null || entries[i].remote.fileId in initial }
            val start = on.copyOf()
            val countLabel = Ui.label("", muted = true)
            val listModel = javax.swing.DefaultListModel<Int>().apply { entries.indices.forEach { addElement(it) } }
            val list = javax.swing.JList(listModel)
            fun recount() { countLabel.text = s.f("tune.range.count", on.count { it }, on.size); list.repaint() }
            list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            list.cellRenderer = javax.swing.ListCellRenderer<Int> { _, i, _, _, _ ->
                val e = entries[i]
                val text = e.remote.versionLabel + (if (e.date.isNotEmpty()) "  (" + e.date.take(10) + ")" else "") + if (e.current) "  ← " + s["tune.range.current"] else ""
                JLabel((if (on[i]) "✔  " else "     ") + text).apply {
                    isOpaque = true
                    border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                    background = if (on[i]) javax.swing.UIManager.getColor("List.selectionBackground") else list.background
                    foreground = if (on[i]) javax.swing.UIManager.getColor("List.selectionForeground") else list.foreground
                }
            }
            var anchor = -1
            var dragValue: Boolean? = null
            val mouse = object : java.awt.event.MouseAdapter() {
                fun indexAt(p: java.awt.Point): Int {
                    val i = list.locationToIndex(p)
                    return if (i >= 0 && list.getCellBounds(i, i).contains(p)) i else -1
                }
                override fun mousePressed(ev: java.awt.event.MouseEvent) {
                    val i = indexAt(ev.point)
                    if (i < 0) return
                    if (ev.isShiftDown && anchor >= 0) {
                        val v = on[anchor]
                        for (k in minOf(anchor, i)..maxOf(anchor, i)) on[k] = v
                        dragValue = v
                    } else {
                        on[i] = !on[i]
                        anchor = i
                        dragValue = on[i]
                    }
                    list.clearSelection()
                    recount()
                }
                override fun mouseDragged(ev: java.awt.event.MouseEvent) {
                    val v = dragValue ?: return
                    val i = list.locationToIndex(ev.point)
                    if (i < 0 || on[i] == v) return
                    on[i] = v
                    list.clearSelection()
                    recount()
                }
                override fun mouseReleased(ev: java.awt.event.MouseEvent) { dragValue = null; list.clearSelection() }
            }
            list.addMouseListener(mouse)
            list.addMouseMotionListener(mouse)
            recount()
            fun save() {
                val picked = entries.filterIndexed { i, _ -> on[i] }
                model.set(row, if (picked.size == entries.size) model.rule(row).copy(allowed = null, allowedLabels = emptyList())
                    else model.rule(row).copy(allowed = picked.map { it.remote.fileId }, allowedLabels = picked.map { it.remote.versionLabel }))
                dlg.dispose()
            }
            EscapeCloser.onEscape(dlg) {
                if (on.contentEquals(start)) dlg.dispose()
                else when (JOptionPane.showConfirmDialog(dlg, s["tune.range.unsaved"], "CrashHunter", JOptionPane.YES_NO_CANCEL_OPTION)) {
                    JOptionPane.YES_OPTION -> save()
                    JOptionPane.NO_OPTION -> dlg.dispose()
                    else -> {}
                }
            }
            content.add(Ui.row(
                Ui.button(null, s["tune.range.selectAll"]) { on.fill(true); recount() },
                Ui.button(null, s["tune.range.selectNone"]) { on.fill(false); recount() },
                countLabel,
            ))
            content.add(Ui.gap(8))
            content.add(JScrollPane(list).apply { preferredSize = Dimension(460, 320); alignmentX = Component.LEFT_ALIGNMENT })
            content.add(Ui.gap(12))
            content.add(Ui.row(Ui.button(null, s["action.save"]) { save() }))
            dlg.pack()
            dlg.setLocationRelativeTo(owner)
        }
        dlg.isVisible = true
    }

    private fun start(inst: GameInstance, rules: Map<String, ModRule>) {
        val launcher = ctx.launcher ?: return
        val install = launcher.installation
        if (ctx.bisectRunning || ctx.fixRunning) { Ui.toast(this, ctx.strings["tune.busy"]); return }
        val s = ctx.strings
        logLines.clear(); logArea.text = ""; round = 0; multi = false; unseen = 0; latestButton.isVisible = false
        details.reset(); changes.clear(); status.reset(); liveNames.clear()
        runStart = System.currentTimeMillis()
        ctx.bisectRunning = true
        showProgress()
        val session = TuneSession(launcher, inst, ctx.settings, ctx.diagnosis, ctx.resolver(), ctx.tuneApplier(), rules, planner = { ctx.fixPlannerFor(it) },
            dbFirst = if (ctx.settings.dbUse) { copy -> DbCheckView.run(ctx, copy, 0).check } else null) { p -> SwingUtilities.invokeLater { onProgress(p) } }
        job = scope.launch {
            var done: TuneReport? = null
            try { withContext(Dispatchers.IO) { done = session.run() } } catch (_: CancellationException) {
            } finally { ctx.bisectRunning = false; ticker.stop() }
            job = null
            val r = done ?: run { showSetup(); return@launch }
            report = r
            showResult(r)
            if (ctx.settings.dbSendRuns) runCatching {
                val env = dev.crashhunter.core.db.DbRefs.env(inst.profile)
                val idx = session.copyIndex
                if (env != null && idx != null) {
                    val run = withContext(Dispatchers.IO) {
                        session.recorder.build(ctx.installId(), VERSION, "tune", env, idx, DbRecorder(ctx).runSettings() + ("rules" to rules.values.groupingBy { it.policy.name }.eachCount().toString()),
                            r.result.status.name, session.lastBisect)
                    }
                    DbRecorder(ctx).submitRun(run, this@TunePanel)
                }
            }
            DbRecorder(ctx).submit(DbRecorder(ctx).fromTune(r), this@TunePanel)
            if (r.result.status == TuneStatus.CANCELLED) return@launch
            TrayNotifier.notify("CrashHunter", "${inst.name}: ${s["tune.done"]}") {
                (SwingUtilities.getWindowAncestor(this@TunePanel) as? MainFrame)?.bringToFront()
            }
        }
    }

    private fun showProgress() {
        val s = ctx.strings
        val card = Ui.card()
        card.add(goalLabel.apply { text = s["tune.phase.copy"]; alignmentX = Component.LEFT_ALIGNMENT })
        card.add(Ui.gap(10))
        card.add(bar)
        card.add(Ui.gap(8))
        card.add(Ui.row(timeLabel.apply { text = "" }))
        slowHint.reset()
        card.add(slowHint.panel)
        card.add(Ui.gap(12))
        card.add(Ui.row(Ui.button(LineIcon.Kind.STOP, s["action.cancel"]) { job?.cancel(); goalLabel.text = s["bisect.state.stopping"] }))
        card.add(Ui.gap(14))
        card.add(status.facts)
        card.add(Ui.gap(14))
        card.add(Ui.collapsible(s["tune.mods.title"], status.mods, true).apply { alignmentX = Component.LEFT_ALIGNMENT })
        card.add(Ui.gap(10))
        val logBox = Ui.vbox().apply { isOpaque = false; add(logScroll); add(Ui.gap(4)); add(Ui.row(latestButton)) }
        card.add(Ui.collapsible(s["tune.log.title"], logBox, true).apply { alignmentX = Component.LEFT_ALIGNMENT })
        card.add(Ui.gap(10))
        card.add(Ui.collapsible(s["details.title"], details.panel, true).apply { alignmentX = Component.LEFT_ALIGNMENT })
        setBody(JPanel(BorderLayout()).apply { isOpaque = false; add(card, BorderLayout.NORTH) })
        ticker.start()
    }

    private fun visible(step: dev.crashhunter.core.tune.TuneStep): Boolean =
        !(step is dev.crashhunter.core.tune.Fixed && step.kind == dev.crashhunter.core.fix.FixKind.ENABLE_PROVIDER)

    private fun versionOf(file: String, fallback: String): String =
        report?.versions?.get(file) ?: fallback.removeSuffix(".jar")

    private fun fixText(kind: dev.crashhunter.core.fix.FixKind, params: Map<String, String>): String {
        val key = "fix.${kind.name}"
        return if (ctx.strings.has(key)) ctx.strings.t(key, params) else kind.name
    }

    private fun name(file: String): String = report?.names?.get(file) ?: liveNames[file] ?: file.removeSuffix(".jar")

    private fun atBottom(): Boolean {
        val sb = logScroll.verticalScrollBar
        return !sb.isVisible || sb.value + sb.visibleAmount >= sb.maximum - 8
    }

    private fun toBottom() {
        unseen = 0
        latestButton.isVisible = false
        SwingUtilities.invokeLater { logScroll.verticalScrollBar.let { it.value = it.maximum } }
    }

    private fun log(text: String) {
        val line = "${java.time.LocalTime.now().withNano(0)}  $text"
        logLines += line
        val follow = atBottom()
        if (logArea.text.isEmpty()) logArea.text = line else logArea.append("\n" + line)
        if (follow) toBottom() else {
            unseen++
            latestButton.text = ctx.strings.f("tune.log.latest", unseen)
            latestButton.isVisible = true
        }
    }

    private fun onProgress(p: TuneProgress) {
        val s = ctx.strings
        when (p) {
            is TuneProgress.Phase -> {
                if (job != null) goalLabel.text = s.f("tune.phase.${p.key}", round)
                when (p.key) {
                    "launch" -> status.stage(TuneStatusView.Stage.LAUNCH)
                    "search" -> status.stage(TuneStatusView.Stage.SEARCH)
                    else -> status.stage(TuneStatusView.Stage.PREP)
                }
            }
            is TuneProgress.Mods -> { liveNames.putAll(p.names); status.snapshot(p) }
            is TuneProgress.Launch -> details.single(p.instance, goalLabel.text, s.f("details.enabled", p.enabled))
            is TuneProgress.Note -> when (p.key) {
                "static" -> {
                    val text = fixText(dev.crashhunter.core.fix.FixKind.valueOf(p.args[0]), p.params)
                    log(s.f("tune.log.static", text))
                    status.stage(TuneStatusView.Stage.STATIC)
                    status.staticFixed()
                }
                "toolOff" -> { log(s.f("tune.log.toolOff", *p.args.toTypedArray())); status.staticFixed() }
                else -> log(s.f("tune.log.${p.key}", *p.args.toTypedArray()))
            }
            is TuneProgress.Db -> {
                val c = p.check
                if (c == null) log(s["tune.log.dbFailed"])
                else if (!DbCheckView.hasProblems(c)) log(s["tune.log.dbNone"])
                else log(s.f("tune.log.db", c.known.broken.size, c.known.conflicts.size, c.known.fixes.size, c.crashes.size))
                if (p.plan.isNotEmpty()) log(s.f("tune.log.dbPlan", p.plan.joinToString(", ") { name(it) }))
            }
            is TuneProgress.Event -> when (val e = p.event) {
                is TuneEvent.Round -> { round = e.number; log(s.f("tune.log.round", e.number)); status.searchEnded() }
                is TuneEvent.Found -> {
                    status.found(e.culprits)
                    status.addFinding(s.f("tune.facts.found", round, e.culprits.joinToString(", ") { name(it) }))
                    log(s.f(if (e.db) "tune.log.foundDb" else if (e.direct) "tune.log.foundDirect" else "tune.log.found", e.culprits.joinToString(", ") { name(it) }))
                }
                is TuneEvent.TryVersion -> {
                    status.stage(TuneStatusView.Stage.TRY)
                    goalLabel.text = s.f("tune.phase.version", name(e.file), e.label, e.index, e.count)
                    log(s.f("tune.log.try", name(e.file), e.label.removeSuffix(".jar")))
                }
                is TuneEvent.VersionResult -> {
                    if (e.result.name == "PASS" || e.newFailure) {
                        changes += s.f("tune.step.replaced", name(e.file), "", e.label.removeSuffix(".jar")).replace("  ", " "); details.changes(changes)
                        status.appendFinding(s.f("tune.facts.versioned", name(e.file), e.label.removeSuffix(".jar")) + " " + s[if (e.result.name == "PASS") "tune.facts.launched" else "tune.facts.changed"])
                    }
                    if (e.result.name == "PASS") status.allLaunched()
                    log(s.f("tune.log.versionResult", e.label, s["tune.trial.${e.result.name}"]) + if (e.newFailure) " " + s["tune.log.newFailure"] else "")
                }
                is TuneEvent.TryFix -> {
                    status.stage(TuneStatusView.Stage.TRY)
                    goalLabel.text = s.f("tune.phase.fix", fixText(e.kind, e.params))
                    log(s.f("tune.log.tryFix", fixText(e.kind, e.params)))
                }
                is TuneEvent.FixResult -> {
                    if (e.result.name == "PASS") status.allLaunched()
                    if (e.result.name == "PASS" || e.newFailure) status.addFinding(s.f("tune.facts.fixed", round, e.target) + " " + s[if (e.result.name == "PASS") "tune.facts.launched" else "tune.facts.changed"])
                    log(s.f("tune.log.versionResult", e.target, s["tune.trial.${e.result.name}"]) + if (e.newFailure) " " + s["tune.log.newFailure"] else "")
                }
                is TuneEvent.Fixed -> if (visible(e.step)) { changes += fixText(e.step.kind, e.step.params); details.changes(changes) }
                is TuneEvent.NoVersions -> log(s.f("tune.log.noVersions", name(e.file)))
                is TuneEvent.TryLoader -> {
                    status.stage(TuneStatusView.Stage.TRY)
                    goalLabel.text = s.f("tune.phase.version", "Forge", e.version, e.index, e.count)
                    log(s.f("tune.log.loader", "Forge", e.version))
                }
                is TuneEvent.LoaderResult -> {
                    if (e.result.name == "PASS" || e.newFailure) { changes += s.f("tune.step.loader", "Forge", "", e.version).replace(":  →", ":"); details.changes(changes) }
                    log(s.f("tune.log.versionResult", e.version, s["tune.trial.${e.result.name}"]) + if (e.newFailure) " " + s["tune.log.newFailure"] else "")
                }
                is TuneEvent.RestoreStart -> {
                    status.stage(TuneStatusView.Stage.TRY)
                    log(s.f("tune.log.restoreStart", e.files.joinToString(", ") { name(it) }))
                    status.addFinding(s.f("tune.facts.restoreStart", e.files.size))
                }
                is TuneEvent.TryRestore -> {
                    goalLabel.text = s.f("tune.phase.restore", name(e.file), e.label.removeSuffix(".jar"), e.index, e.count)
                    log(s.f("tune.log.tryRestore", name(e.file), e.label.removeSuffix(".jar")))
                }
                is TuneEvent.RestoreResult -> log(s.f("tune.log.versionResult", e.label.removeSuffix(".jar"), s["tune.trial.${e.result.name}"]))
                is TuneEvent.Restored -> {
                    status.restored(e.files)
                    status.addFinding(s.f("tune.facts.restored", name(e.file), e.label.removeSuffix(".jar")))
                    changes += s.f("tune.step.replaced", name(e.file), "", e.label.removeSuffix(".jar")).replace("  ", " "); details.changes(changes)
                    log(s.f("tune.log.restored", name(e.file), e.label.removeSuffix(".jar")))
                }
                is TuneEvent.RemovedMods -> {
                    status.removedMods(listOf(e.file) + e.also)
                    status.appendFinding(s.f("tune.facts.removed", (listOf(e.file) + e.also).joinToString(", ") { name(it) }))
                    changes += s.f("tune.step.removed", name(e.file)) + if (e.also.isNotEmpty()) " " + s.f("tune.log.also", e.also.joinToString(", ") { name(it) }) else ""
                    details.changes(changes)
                    log(s.f("tune.log.removed", name(e.file)) + if (e.also.isNotEmpty()) " " + s.f("tune.log.also", e.also.joinToString(", ") { name(it) }) else "")
                }
            }
            is TuneProgress.Bisect -> when (val b = p.progress) {
                is BisectProgress.Parallel -> { multi = b.count > 1; details.slots(b.instances) }
                is BisectProgress.Mods -> { details.mods(b); liveNames.putAll(b.names); status.names(b.names) }
                is BisectProgress.TrialDone -> status.trialDone(b)
                is BisectProgress.Trial -> { details.trial(b); status.trial(b); status.stage(TuneStatusView.Stage.SEARCH); if (b.state.isEmpty()) goalLabel.text = s.f("tune.phase.searching", round, b.number, maxOf(b.estimate, b.number)) }
                is BisectProgress.Log -> if (b.key.startsWith("state.") || b.key.startsWith("done.") || b.key == "result.FOUND") {
                    val tag = b.slot?.let { "#${it + 1}  " } ?: ""
                    val fmt = s["log.${b.key}"]
                    val text = if (fmt == "log.${b.key}") b.key else runCatching { String.format(fmt, *b.args.toTypedArray()) }.getOrDefault(fmt)
                    log(tag + text.trimEnd())
                }
                else -> {}
            }
        }
    }

    private fun showResult(r: TuneReport) {
        val s = ctx.strings
        val card = Ui.card()
        val st = r.result.status
        val dot = JLabel(DotIcon(when (st) { TuneStatus.LAUNCHED -> StatusColors.ok; TuneStatus.CANCELLED -> StatusColors.neutral; else -> StatusColors.error }))
        card.add(Ui.row(dot, Ui.label(s["tune.result.${st.name}"], 16f, bold = true), gap = 8))
        card.add(Ui.gap(6))
        card.add(Ui.label(s.f("bisect.elapsed", clock(System.currentTimeMillis() - runStart)), muted = true))
        if (st == TuneStatus.BLOCKED) {
            card.add(Ui.gap(8))
            card.add(Ui.wrapText(s.f("tune.blocked", r.result.blockedBy.joinToString(", ") { name(it) })))
        }
        val message = if (r.result.message == dev.crashhunter.core.bisect.PRISM_UNRESPONSIVE) s["bisect.prismUnresponsive"] else r.result.message
        if (message.isNotBlank()) { card.add(Ui.gap(6)); card.add(Ui.wrapText(message, muted = true)) }
        card.add(Ui.gap(12))
        if (r.result.steps.none { visible(it) }) card.add(Ui.label(s["tune.noChanges"], muted = true))
        for (step in r.result.steps.filter { visible(it) }) {
            val text = when (step) {
                is Replaced -> s.f("tune.step.replaced", name(step.file), versionOf(step.file, step.remote.versionLabel), versionOf(step.newFile, step.remote.versionLabel))
                is dev.crashhunter.core.tune.Fixed -> fixText(step.kind, step.params)
                is dev.crashhunter.core.tune.LoaderChanged -> s.f("tune.step.loader", r.source.profile.loader.display, step.from, step.to)
                is Removed -> s.f("tune.step.removed", name(step.file)) + if (step.alsoRemoved.isNotEmpty()) " " + s.f("tune.log.also", step.alsoRemoved.joinToString(", ") { name(it) }) else ""
            }
            card.add(Ui.row(JLabel(DotIcon(if (step is Removed) StatusColors.warn else StatusColors.running)), Ui.label(text), gap = 8))
            card.add(Ui.gap(4))
        }
        card.add(Ui.gap(14))
        val buttons = mutableListOf<JComponent>()
        if (applied == null && st == TuneStatus.LAUNCHED && r.result.steps.isNotEmpty()) buttons += Ui.button(LineIcon.Kind.PLAY, s["tune.apply"]) { apply(r) }
        if (applied != null) buttons += Ui.button(null, s["tune.undo"]) { undo() }
        buttons += Ui.button(null, if (applied == null) s["tune.discard"] else s["tune.back"]) { discard(r) }
        card.add(Ui.row(*buttons.toTypedArray()))
        if (logLines.isNotEmpty()) {
            card.add(Ui.gap(14))
            val area = Ui.wrapText(logLines.joinToString("\n"), mono = true).apply { lineWrap = false; rows = 12 }
            card.add(Ui.collapsible(s["bisect.log"], JScrollPane(area).apply { preferredSize = Dimension(400, 220); maximumSize = Dimension(Int.MAX_VALUE, 220) }, false).apply { alignmentX = Component.LEFT_ALIGNMENT })
        }
        setBody(JPanel(BorderLayout()).apply { isOpaque = false; add(card, BorderLayout.NORTH) })
    }

    private fun apply(r: TuneReport) {
        val s = ctx.strings
        val ok = JOptionPane.showConfirmDialog(this, s.f("tune.apply.confirm", r.source.name), "CrashHunter", JOptionPane.OK_CANCEL_OPTION)
        if (ok != JOptionPane.OK_OPTION) return
        scope.launch {
            val rec = withContext(Dispatchers.IO) { runCatching { ctx.fixApplier().apply(r.source, TuneSession.proposals(r.result, r.names)) } }
            rec.onSuccess { applied = it; Ui.toast(this@TunePanel, s["tune.applied"]); r.copy?.let { c -> withContext(Dispatchers.IO) { WorkCopy.delete(c.dir) } } }
                .onFailure { JOptionPane.showMessageDialog(this@TunePanel, s.f("tune.apply.failed", it.message ?: "")) }
            showResult(r)
        }
    }

    private fun undo() {
        val rec = applied ?: return
        scope.launch {
            withContext(Dispatchers.IO) { ctx.fixApplier().undo(rec) }
            applied = null
            Ui.toast(this@TunePanel, ctx.strings["tune.undone"])
            showSetup()
        }
    }

    private fun discard(r: TuneReport) {
        scope.launch {
            r.copy?.let { c -> withContext(Dispatchers.IO) { WorkCopy.delete(c.dir) } }
            showSetup()
        }
    }
}

package dev.crashhunter.app

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.util.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.UIManager

class DiagnosisPanel(private val ctx: AppContext, private val frame: MainFrame) : JPanel(BorderLayout()) {
    class Item(val source: CrashSource, @Volatile var diagnosis: Diagnosis? = null, val db: DbCheckResult? = null)

    private val scope = MainScope()
    private var loadJob: Job? = null
    private val model = DefaultListModel<Item>()
    private val list = JList(model)
    private val detail = JPanel(BorderLayout())
    private var instance: GameInstance? = null
    private var index: ModIndex? = null
    private val dateFmt = SimpleDateFormat("MM/dd HH:mm")
    @Volatile var wantFile: File? = null

    val latestCrash: Diagnosis?
        get() = (0 until model.size()).map { model[it] }.firstOrNull { it.source.kind == SourceKind.CRASH_REPORT || it.source.kind == SourceKind.HS_ERR }?.diagnosis
    val selected: Diagnosis? get() = list.selectedValue?.diagnosis

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ItemRenderer()
        list.fixedCellHeight = 52
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }

        val left = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 16, 16, 0)
            add(JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(4, 0, 6, 0)
                add(Ui.label(ctx.strings["diag.list"], bold = true), BorderLayout.WEST)
                add(Ui.iconButton(LineIcon.Kind.FILE, ctx.strings["diag.openFile"]) { chooseFile() }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JScrollPane(list).apply { border = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")) }, BorderLayout.CENTER)
            preferredSize = Dimension(300, 0)
        }
        detail.border = BorderFactory.createEmptyBorder(0, 16, 16, 16)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, detail).apply { dividerSize = 6; border = null; resizeWeight = 0.0 }, BorderLayout.CENTER)

        dropTarget = object : DropTarget() {
            override fun drop(e: DropTargetDropEvent) {
                e.acceptDrop(DnDConstants.ACTION_COPY)
                @Suppress("UNCHECKED_CAST")
                (e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>)?.firstOrNull()?.let { diagnoseFile(it) }
            }
        }
        frame.onInstanceChange { load(it) }
    }

    fun load(inst: GameInstance?) {
        instance = inst
        loadJob?.cancel()
        model.clear()
        if (inst == null) { showEmpty(); return }
        showMessage(ctx.strings["diag.loading"])
        loadJob = scope.launch {
            val (idx, sources) = withContext(Dispatchers.IO) { ModIndex.load(inst.modsDir) to ctx.diagnosis.sources(inst) }
            index = idx
            sources.forEach { model.addElement(Item(it)) }
            val wanted = wantFile?.let { w -> (0 until model.size()).firstOrNull { model[it].source.file?.absolutePath == w.absolutePath } }
            wantFile = null
            val firstCrash = wanted ?: (0 until model.size()).firstOrNull { model[it].source.kind == SourceKind.CRASH_REPORT || model[it].source.kind == SourceKind.HS_ERR } ?: 0
            for (i in 0 until model.size()) {
                val item = model[i]
                item.diagnosis = withContext(Dispatchers.IO) { runCatching { ctx.diagnosis.diagnose(item.source, inst, idx) }.getOrNull() }
                list.repaint()
                if (i == firstCrash) { list.selectedIndex = i; showSelected() }
            }
            frame.bisectPage.diagnosesLoaded()
            if (ctx.settings.dbUse) {
                val r = withContext(Dispatchers.IO) { runCatching { DbCheckView.run(ctx, inst) }.getOrNull() }
                if (r != null && instance === inst && DbCheckView.hasProblems(r.check)) {
                    val at = (0 until model.size()).firstOrNull { model[it].source.kind != SourceKind.MODS_CHECK } ?: model.size()
                    model.add(at, Item(CrashSource(SourceKind.MODS_CHECK, inst.modsDir, System.currentTimeMillis()), null, r))
                }
            }
        }
    }

    fun selectFile(file: File?) {
        if (file == null) return
        for (i in 0 until model.size()) if (model[i].source.file?.absolutePath == file.absolutePath) { list.selectedIndex = i; list.ensureIndexIsVisible(i); return }
    }

    private fun chooseFile() {
        val ch = JFileChooser(instance?.gameDir)
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) diagnoseFile(ch.selectedFile)
    }

    private fun diagnoseFile(f: File) {
        showMessage(ctx.strings["diag.loading"])
        scope.launch {
            val d = withContext(Dispatchers.IO) { ctx.diagnosis.diagnoseText(f.readText(), f, index) }
            val item = Item(d.source, d)
            model.add(0, item)
            list.selectedIndex = 0
        }
    }

    private fun showSelected() {
        val item = list.selectedValue ?: return
        item.db?.let { r -> instance?.let { showDb(it, r) }; return }
        val d = item.diagnosis
        if (d == null) { showMessage(ctx.strings["diag.loading"]); return }
        detail.removeAll()
        val body = Ui.vbox()
        body.add(ResultCards.diagnosis(ctx, d, instance, withDetails = false, onBisect = { frame.startBisectFor(d) }).apply { alignmentX = Component.LEFT_ALIGNMENT })
        instance?.let { inst ->
            if (d.source.file != null || d.source.kind == SourceKind.MODS_CHECK) {
                body.add(Ui.gap(12))
                body.add(FixCard(ctx, inst, listOf(d)) { out -> if (out.verify == dev.crashhunter.core.fix.VerifyResult.NEW_FAILURE) load(inst) }.apply { alignmentX = Component.LEFT_ALIGNMENT })
            }
        }
        ResultCards.details(ctx, d)?.let { body.add(Ui.gap(12)); body.add(it.apply { alignmentX = Component.LEFT_ALIGNMENT }) }
        frame.clearPending(instance?.id)
        detail.add(JScrollPane(JPanel(BorderLayout()).apply { isOpaque = false; add(body, BorderLayout.NORTH) }).apply {
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            
        }, BorderLayout.CENTER)
        detail.revalidate(); detail.repaint()
    }

    private fun showDb(inst: GameInstance, r: DbCheckResult) {
        detail.removeAll()
        detail.add(JScrollPane(MarkdownView(DbCheckView.markdown(ctx, inst, r))).apply { border = null }, BorderLayout.CENTER)
        detail.revalidate(); detail.repaint()
    }

    private fun showMessage(text: String) {
        detail.removeAll()
        if (text == ctx.strings["diag.loading"]) detail.add(JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; add(Ui.loading(text)) }, BorderLayout.CENTER)
        else detail.add(Ui.label(text, muted = true).apply { horizontalAlignment = JLabel.CENTER }, BorderLayout.CENTER)
        detail.revalidate(); detail.repaint()
    }

    private fun showEmpty() {
        detail.removeAll()
        detail.add(EmptyState.create(ctx, frame), BorderLayout.CENTER)
        detail.revalidate(); detail.repaint()
    }

    private inner class ItemRenderer : ListCellRenderer<Item> {
        private val panel = JPanel(BorderLayout(8, 0))
        private val dot = JLabel()
        private val top = JLabel()
        private val bottom = JLabel()

        init {
            panel.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            val text = Ui.vbox().apply { add(top); add(bottom) }
            panel.add(dot, BorderLayout.WEST)
            panel.add(text, BorderLayout.CENTER)
            top.font = top.font.deriveFont(11f)
            bottom.font = bottom.font.deriveFont(Font.BOLD)
        }

        override fun getListCellRendererComponent(list: JList<out Item>, value: Item, index: Int, sel: Boolean, focus: Boolean): Component {
            val d = value.diagnosis
            value.db?.let { r ->
                top.text = ctx.strings["kind.DB_CHECK"]
                bottom.text = DbCheckView.headline(ctx, r.check)
                dot.icon = DotIcon(if (r.check.known.broken.isNotEmpty() || r.check.known.conflicts.isNotEmpty()) StatusColors.error else StatusColors.warn)
                panel.background = if (sel) list.selectionBackground else list.background
                top.foreground = if (sel) list.selectionForeground else UIManager.getColor("Label.disabledForeground")
                bottom.foreground = if (sel) list.selectionForeground else list.foreground
                panel.isOpaque = true
                return panel
            }
            val kind = ctx.strings["kind.${value.source.kind.name}"]
            val time = if (value.source.kind == SourceKind.MODS_CHECK) "" else dateFmt.format(Date(value.source.timeMs)) + "  "
            top.text = time + kind
            bottom.text = when {
                d == null -> "…"
                d.cause == Cause.NO_PROBLEM -> ctx.strings["diag.noProblem"]
                else -> d.topSuspect?.name ?: d.title
            }
            dot.icon = DotIcon(colorOf(d))
            panel.background = if (sel) list.selectionBackground else list.background
            top.foreground = if (sel) list.selectionForeground else UIManager.getColor("Label.disabledForeground")
            bottom.foreground = if (sel) list.selectionForeground else list.foreground
            panel.isOpaque = true
            return panel
        }
    }

    companion object {
        fun colorOf(d: Diagnosis?) = when {
            d == null -> StatusColors.neutral
            d.cause == Cause.NO_PROBLEM -> StatusColors.ok
            d.source.kind == SourceKind.MODS_CHECK || d.source.kind == SourceKind.LATEST_LOG -> StatusColors.warn
            else -> StatusColors.error
        }

        fun report(ctx: AppContext, d: Diagnosis): String = Redactor.redact(buildString {
            appendLine("[CrashHunter] ${d.title}")
            appendLine(d.detail)
            d.topSuspect?.let { appendLine("${ctx.strings["card.culprit"]}: ${it.name}${it.jar?.let { j -> " ($j)" } ?: ""}") }
            d.solutions.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
            d.otherProblems.forEach { appendLine("- $it") }
            d.source.file?.let { appendLine("${ctx.strings["card.source"]}: ${it.name}") }
            d.parsed?.excerpt?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it.lines().take(12).joinToString("\n")) }
        })
    }
}

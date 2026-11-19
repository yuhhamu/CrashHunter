package dev.crashhunter.app

import dev.crashhunter.core.bisect.BisectProgress
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.tune.TuneProgress
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class TuneStatusView(private val ctx: AppContext) {
    enum class ModState(val color: Color) {
        CULPRIT(StatusColors.error), TESTING(StatusColors.running), CANDIDATE(StatusColors.warn), FIXED(Color(0x8B, 0x5C, 0xF6)),
        REMOVED(StatusColors.neutral), CONFIRMED(StatusColors.ok), UNKNOWN(Color(0x6B, 0x70, 0x76))
    }

    enum class Stage { PREP, STATIC, LAUNCH, SEARCH, TRY, DONE }

    private val s get() = ctx.strings
    val facts = Ui.vbox().apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    val mods = Ui.vbox().apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }

    private val stageLabels = Stage.entries.associateWith { Ui.label("", 12f) }
    private val stageRow = JPanel(WrapLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    private val focusLabel = Ui.label("", bold = true)
    private val focusBar = JProgressBar(0, 1).apply { maximumSize = Dimension(Int.MAX_VALUE, 8); preferredSize = Dimension(200, 8); alignmentX = Component.LEFT_ALIGNMENT }
    private val countsLabel = Ui.label("", muted = true)
    private val foundBox = Ui.vbox().apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    private val foundLines = mutableListOf<String>()

    private val names = HashMap<String, String>()
    private val versions = HashMap<String, String>()
    private val initialFile = LinkedHashMap<String, String>()
    private var enabled: Set<String> = emptySet()
    private val removed = HashSet<String>()
    private val culprits = HashSet<String>()
    private val confirmed = HashSet<String>()
    private var focus: Set<String> = emptySet()
    private var pool = 0
    private var total = 0
    private val running = HashMap<Int, Set<String>>()
    private val byNumber = HashMap<Int, Set<String>>()
    private var staticCount = 0
    private var stage = Stage.PREP
    private var filter: ModState? = null

    private data class Row(val name: String, val state: ModState, val change: String)
    private var rows: List<Row> = emptyList()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(c: Int) = s["tune.mods.col.$c"]
        override fun getValueAt(r: Int, c: Int): Any = when (c) { 0 -> rows[r].name; 1 -> rows[r].state; else -> rows[r].change }
    }
    private val table = JTable(model).apply {
        fillsViewportHeight = true
        rowHeight = 22
        setShowGrid(false)
        autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        columnModel.getColumn(0).preferredWidth = 360
        columnModel.getColumn(1).preferredWidth = 110
        columnModel.getColumn(1).maxWidth = 140
        columnModel.getColumn(2).preferredWidth = 140
        columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, r: Int, c: Int): Component {
                super.getTableCellRendererComponent(t, "", sel, focus, r, c)
                val st = v as? ModState
                icon = st?.let { DotIcon(it.color) }
                text = st?.let { s["tune.mod.${it.name}"] } ?: ""
                return this
            }
        }
    }
    private val chips = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    private val refresher = javax.swing.Timer(400) { rebuild() }.apply { isRepeats = false }

    init {
        Stage.entries.forEachIndexed { i, st ->
            stageRow.add(stageLabels.getValue(st).apply { text = (if (i > 0) "  →  " else "") + s["tune.stage.${st.name}"] })
        }
        facts.add(stageRow)
        facts.add(Ui.gap(10))
        facts.add(focusLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        facts.add(Ui.gap(4))
        facts.add(focusBar)
        facts.add(Ui.gap(6))
        facts.add(countsLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        facts.add(Ui.gap(10))
        facts.add(Ui.label(s["tune.facts.title"], bold = true).apply { alignmentX = Component.LEFT_ALIGNMENT })
        facts.add(Ui.gap(4))
        facts.add(foundBox)
        mods.add(chips)
        mods.add(Ui.gap(4))
        mods.add(JScrollPane(table).apply { preferredSize = Dimension(400, 260); maximumSize = Dimension(Int.MAX_VALUE, 260); alignmentX = Component.LEFT_ALIGNMENT })
        reset()
    }

    fun reset() {
        names.clear(); versions.clear(); initialFile.clear(); enabled = emptySet()
        removed.clear(); culprits.clear(); confirmed.clear(); focus = emptySet(); pool = 0; total = 0
        running.clear(); byNumber.clear(); staticCount = 0; filter = null; foundLines.clear()
        stage(Stage.PREP)
        renderFound()
        rebuild()
    }

    fun stage(st: Stage) {
        stage = st
        Stage.entries.forEach { e ->
            val l = stageLabels.getValue(e)
            l.font = l.font.deriveFont(if (e == st) java.awt.Font.BOLD else java.awt.Font.PLAIN)
            l.foreground = if (e == st) StatusColors.running else javax.swing.UIManager.getColor("Label.disabledForeground")
        }
    }

    fun snapshot(p: TuneProgress.Mods) {
        names.putAll(p.names); versions.putAll(p.versions)
        if (initialFile.isEmpty()) p.enabled.forEach { f -> initialFile.putIfAbsent(nameOf(f), f) }
        enabled = p.enabled
        later()
    }

    fun names(map: Map<String, String>) { names.putAll(map) }

    fun staticFixed() { staticCount++; later() }

    fun trial(t: BisectProgress.Trial) {
        if (t.state.isNotEmpty()) return
        running[t.slot] = t.enabled
        byNumber[t.number] = t.enabled
        if (t.focus.isNotEmpty()) focus = t.focus.toSet()
        pool = t.pool
        total = t.total
        later()
    }

    fun trialDone(d: BisectProgress.TrialDone) {
        running.remove(d.slot)
        val set = byNumber.remove(d.number)
        if (d.result == TrialResult.PASS && set != null) confirmed += set.map { nameOf(it) }
        later()
    }

    fun searchEnded() { focus = emptySet(); running.clear(); pool = 0; later() }

    fun found(files: List<String>) { culprits += files.map { nameOf(it) }; searchEnded() }

    fun removedMods(files: List<String>) { removed += files.map { nameOf(it) }; later() }

    fun restored(files: List<String>) { removed -= files.map { nameOf(it) }.toSet(); later() }

    fun allLaunched() { confirmed += enabled.map { nameOf(it) }; later() }

    fun addFinding(text: String) { foundLines += text; renderFound() }

    fun appendFinding(text: String) {
        if (foundLines.isEmpty()) foundLines += text.trim() else foundLines[foundLines.lastIndex] = foundLines.last() + " " + text.trim()
        renderFound()
    }

    private fun renderFound() {
        foundBox.removeAll()
        if (foundLines.isEmpty()) foundBox.add(Ui.label(s["tune.facts.empty"], muted = true).apply { alignmentX = Component.LEFT_ALIGNMENT })
        foundLines.forEach { line ->
            foundBox.add(object : javax.swing.JTextArea(line) {
                override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
            }.apply {
                lineWrap = true; wrapStyleWord = true; isEditable = false; isOpaque = false; border = null; font = javax.swing.UIManager.getFont("Label.font")
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        foundBox.revalidate(); foundBox.repaint()
    }

    private fun nameOf(file: String) = names[file] ?: file.removeSuffix(".jar")

    private fun later() { refresher.restart() }

    private fun stateOf(name: String, current: String?): Pair<ModState, String> {
        val first = initialFile[name]
        if (name in removed || (first != null && current == null)) return ModState.REMOVED to ""
        if (first != null && current != null && current != first) return ModState.FIXED to "${versions[first] ?: first.removeSuffix(".jar")} → ${versions[current] ?: current.removeSuffix(".jar")}"
        if (first == null && current != null) return ModState.FIXED to s["tune.mods.added"]
        if (name in culprits) return ModState.CULPRIT to ""
        if (current != null && current in focus && running.values.any { current in it }) return ModState.TESTING to ""
        if (current != null && current in focus) return ModState.CANDIDATE to ""
        if (name in confirmed) return ModState.CONFIRMED to ""
        return ModState.UNKNOWN to ""
    }

    private fun rebuild() {
        val current = HashMap<String, String>()
        enabled.forEach { f -> current[nameOf(f)] = f }
        val all = (initialFile.keys + current.keys).distinct()
        val list = all.map { n -> stateOf(n, current[n]).let { (st, ch) -> Row(n, st, ch) } }
        val counts = list.groupingBy { it.state }.eachCount()
        rows = list.filter { filter == null || it.state == filter }.sortedWith(compareBy<Row>({ it.state.ordinal }, { it.name.lowercase() }))
        model.fireTableDataChanged()

        if (pool > 0 && total > 0) {
            focusLabel.text = s.f("tune.facts.candidates", pool, total)
            focusBar.maximum = total
            focusBar.value = total - pool
            focusBar.isVisible = true
        } else {
            focusLabel.text = s.f("tune.facts.confirmed", counts[ModState.CONFIRMED] ?: 0)
            focusBar.isVisible = false
        }
        countsLabel.text = listOfNotNull(
            s.f("tune.facts.confirmed", counts[ModState.CONFIRMED] ?: 0),
            staticCount.takeIf { it > 0 }?.let { s.f("tune.facts.static", it) },
        ).joinToString("   ")

        chips.removeAll()
        val group = javax.swing.ButtonGroup()
        fun chip(text: String, st: ModState?, n: Int) {
            val b = JToggleButton(text + "  " + n, st?.let { DotIcon(it.color) }).apply {
                isSelected = filter == st
                isFocusPainted = false
                border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                addActionListener { filter = st; rebuild() }
            }
            group.add(b)
            chips.add(b)
        }
        chip(s["tune.mods.all"], null, list.size)
        ModState.entries.forEach { st -> counts[st]?.let { chip(s["tune.mod.${st.name}"], st, it) } }
        chips.revalidate(); chips.repaint()
    }
}

class WrapLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {
    override fun layoutContainer(target: java.awt.Container) {
        super.layoutContainer(target)
        val want = preferredLayoutSize(target).height
        if (target !is javax.swing.JComponent || target.height <= 0 || want == target.height) return
        if (target.getClientProperty(WANT) == want) return
        target.putClientProperty(WANT, want)
        javax.swing.SwingUtilities.invokeLater { target.revalidate() }
    }

    private companion object { const val WANT = "crashhunter.wrapWant" }

    override fun preferredLayoutSize(target: java.awt.Container) = size(target, true)
    override fun minimumLayoutSize(target: java.awt.Container) = size(target, false).also { it.width -= hgap + 1 }

    private fun size(target: java.awt.Container, preferred: Boolean): Dimension = synchronized(target.treeLock) {
        var width = target.size.width
        var c: java.awt.Container? = target
        while (width == 0 && c?.parent != null) { c = c.parent; width = c.size.width }
        if (width == 0) width = Int.MAX_VALUE
        val insets = target.insets
        val max = width - (insets.left + insets.right + hgap * 2)
        val dim = Dimension(0, 0)
        var rowW = 0
        var rowH = 0
        for (i in 0 until target.componentCount) {
            val m = target.getComponent(i)
            if (!m.isVisible) continue
            val d = if (preferred) m.preferredSize else m.minimumSize
            if (rowW + d.width > max) {
                dim.width = maxOf(dim.width, rowW); dim.height += rowH + vgap
                rowW = 0; rowH = 0
            }
            if (rowW != 0) rowW += hgap
            rowW += d.width
            rowH = maxOf(rowH, d.height)
        }
        dim.width = maxOf(dim.width, rowW); dim.height += rowH
        dim.width += insets.left + insets.right + hgap * 2
        dim.height += insets.top + insets.bottom + vgap * 2
        dim
    }
}

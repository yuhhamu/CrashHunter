package dev.crashhunter.app

import dev.crashhunter.core.bisect.BisectProgress
import java.awt.Component
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

class InstanceDetailsView(private val ctx: AppContext) {
    val panel = Ui.vbox().apply { alignmentX = Component.LEFT_ALIGNMENT }
    private val grid = JPanel(GridLayout(0, 2, 10, 10)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    private var names: Map<String, String> = emptyMap()
    private var candidates: List<String> = emptyList()
    private var instances: List<String> = emptyList()
    private val cards = HashMap<Int, Card>()
    private val trials = HashMap<Int, BisectProgress.Trial>()
    private val changesCard = Card().apply { root.isVisible = false }

    private class Card {
        val title = Ui.label("", bold = true)
        val sub = Ui.label("", 11f, muted = true)
        val suspected = Ui.label("")
        val suspectedNames = Ui.label("", 11f, muted = true)
        val off = Ui.label("", 11f, muted = true)
        val root = Ui.card().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(javax.swing.UIManager.getColor("Component.borderColor") ?: java.awt.Color.LIGHT_GRAY, 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12),
            )
            listOf(title, sub, Ui.gap(6), suspected, suspectedNames, Ui.gap(2), off).forEach { add(it); (it as? JLabel)?.alignmentX = Component.LEFT_ALIGNMENT }
        }
    }

    init {
        panel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                val cols = if (panel.width in 1 until 620) 1 else 2
                if ((grid.layout as GridLayout).columns != cols) { grid.layout = GridLayout(0, cols, 10, 10); grid.revalidate() }
            }
        })
        panel.add(changesCard.root.apply { alignmentX = Component.LEFT_ALIGNMENT })
        panel.add(Ui.gap(10))
        panel.add(grid)
    }

    fun reset() {
        names = emptyMap(); candidates = emptyList(); instances = emptyList()
        cards.clear(); trials.clear(); grid.removeAll()
        changesCard.root.isVisible = false
        panel.revalidate(); panel.repaint()
    }

    fun mods(p: BisectProgress.Mods) { names = names + p.names; candidates = p.candidates }

    fun slots(ids: List<String>) {
        instances = ids
        cards.clear(); grid.removeAll()
        ids.forEachIndexed { i, id ->
            val c = Card()
            c.title.text = tag(i) + ctx.strings["details.waiting"]
            c.sub.text = id
            cards[i] = c
            grid.add(c.root)
        }
        trials.forEach { (slot, t) -> render(slot, t) }
        panel.revalidate(); panel.repaint()
    }

    fun single(id: String, title: String, note: String) {
        if (instances != listOf(id) || cards[0] == null) {
            instances = listOf(id)
            trials.clear(); cards.clear(); grid.removeAll()
            cards[0] = Card().also { grid.add(it.root) }
        }
        val c = cards[0] ?: return
        c.title.text = title
        c.sub.text = id
        c.suspected.text = note
        c.suspectedNames.text = ""
        c.off.text = ""
        panel.revalidate(); panel.repaint()
    }

    fun changes(lines: List<String>) {
        val c = changesCard
        c.title.text = ctx.strings.f("details.changed", lines.size)
        c.sub.text = ""
        c.suspected.text = ""
        c.suspectedNames.text = short(lines, 4)
        c.off.text = ""
        c.root.isVisible = lines.isNotEmpty()
        panel.revalidate(); panel.repaint()
    }

    fun trial(t: BisectProgress.Trial) {
        if (t.state.isNotEmpty()) return
        trials[t.slot] = t
        render(t.slot, t)
    }

    private fun tag(slot: Int) = if (instances.size > 1) "#${slot + 1}  " else ""

    private fun short(items: List<String>, max: Int): String {
        if (items.isEmpty()) return ""
        val head = items.take(max).joinToString(", ")
        return if (items.size > max) head + ctx.strings.f("details.more", items.size - max) else head
    }

    private fun render(slot: Int, t: BisectProgress.Trial) {
        val c = cards[slot] ?: return
        val s = ctx.strings
        val suspected = t.focus.ifEmpty { candidates }
        val off = candidates.count { it !in t.enabled }
        c.title.text = tag(slot) + s.f("details.trial", t.number, s["log.phase.${t.phase}"])
        c.sub.text = instances.getOrElse(slot) { "" }
        c.suspected.text = s.f("details.focus", suspected.size)
        c.suspectedNames.text = if (suspected.size <= 6) short(suspected.map { names[it] ?: it.removeSuffix(".jar") }.sortedBy { it.lowercase() }, 6) else ""
        c.off.text = s.f("details.disabled", off)
    }
}

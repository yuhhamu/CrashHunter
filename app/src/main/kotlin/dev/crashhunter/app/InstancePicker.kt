package dev.crashhunter.app

import dev.crashhunter.core.model.GameInstance
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class InstancePicker(private val ctx: AppContext) : JPanel(BorderLayout()) {
    private var items: List<GameInstance> = emptyList()
    var selected: GameInstance? = null
        private set
    private val listeners = mutableListOf<(GameInstance?) -> Unit>()
    fun onSelect(l: (GameInstance?) -> Unit) { listeners += l }

    private val button = JButton().apply {
        horizontalAlignment = SwingConstants.LEFT
        horizontalTextPosition = SwingConstants.LEFT
        icon = LineIcon(LineIcon.Kind.CHEVRON_DOWN, 14)
        iconTextGap = 8
        addActionListener { open("") }
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent) {
                if (!e.keyChar.isISOControl() && !e.isControlDown && !e.isAltDown) { open(e.keyChar.toString()); e.consume() }
            }
        })
    }
    private val search = JTextField().apply { putClientProperty("JTextField.placeholderText", ctx.strings["instance.search"]) }
    private val model = DefaultListModel<GameInstance>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean): Component {
                val c = super.getListCellRendererComponent(list, value, index, sel, focus) as JLabel
                (value as? GameInstance)?.let { c.text = label(it) }
                c.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                return c
            }
        }
    }
    private val popup = JPopupMenu().apply {
        layout = BorderLayout()
        add(JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            add(search, BorderLayout.NORTH)
            add(JScrollPane(list).apply { border = null }, BorderLayout.CENTER)
        })
    }

    init {
        isOpaque = false
        add(button)
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
        })
        search.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> move(1)
                    KeyEvent.VK_UP -> move(-1)
                    KeyEvent.VK_PAGE_DOWN -> move(10)
                    KeyEvent.VK_PAGE_UP -> move(-10)
                    KeyEvent.VK_ENTER -> list.selectedValue?.let { pick(it) }
                    KeyEvent.VK_ESCAPE -> popup.isVisible = false
                    else -> return
                }
                e.consume()
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val i = list.locationToIndex(e.point)
                if (i >= 0 && list.getCellBounds(i, i)?.contains(e.point) == true) pick(model[i])
            }
        })
        refreshText()
    }

    fun setItems(newItems: List<GameInstance>, keepId: String?): Boolean {
        items = newItems
        val next = newItems.firstOrNull { it.id == (selected?.id ?: keepId) } ?: newItems.firstOrNull { it.id == keepId } ?: newItems.firstOrNull()
        val changed = next != selected
        selected = next
        refreshText()
        if (popup.isVisible) filter()
        if (changed) listeners.forEach { it(selected) }
        return changed
    }

    fun openSearch() = open("")

    fun selectId(id: String): Boolean {
        val inst = items.firstOrNull { it.id == id } ?: return false
        if (inst == selected) return false
        pick(inst)
        return true
    }

    private fun open(initial: String) {
        if (items.isEmpty()) return
        search.text = initial
        filter()
        popup.preferredSize = Dimension(maxOf(width, 360), minOf(420, 70 + 26 * maxOf(4, minOf(items.size, 12))))
        popup.show(this, 0, height)
        SwingUtilities.invokeLater { search.requestFocusInWindow(); search.caretPosition = search.text.length }
    }

    private fun filter() {
        val words = search.text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shown = items.filter { inst ->
            val hay = "${inst.name} ${inst.id} ${inst.profile.label}".lowercase()
            words.all { it in hay }
        }
        model.clear()
        shown.forEach { model.addElement(it) }
        val idx = shown.indexOfFirst { it.id == selected?.id }.takeIf { it >= 0 && words.isEmpty() } ?: 0
        if (shown.isNotEmpty()) { list.selectedIndex = idx; list.ensureIndexIsVisible(idx) }
    }

    private fun move(d: Int) {
        if (model.isEmpty) return
        val i = (list.selectedIndex + d).coerceIn(0, model.size() - 1)
        list.selectedIndex = i
        list.ensureIndexIsVisible(i)
    }

    private fun pick(inst: GameInstance) {
        popup.isVisible = false
        val changed = inst != selected
        selected = inst
        refreshText()
        button.requestFocusInWindow()
        if (changed) listeners.forEach { it(inst) }
    }

    fun refreshText() {
        button.text = selected?.let { label(it) } ?: ctx.strings["instance.none"]
        search.putClientProperty("JTextField.placeholderText", ctx.strings["instance.search"])
    }

    private fun label(i: GameInstance) = "<html>${MainFrame.esc(i.name)} <span style='color:gray'>${MainFrame.esc(i.profile.label)}</span></html>"
}

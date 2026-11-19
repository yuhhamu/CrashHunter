package dev.crashhunter.app

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

object Ui {
    fun iconButton(icon: LineIcon.Kind, tooltip: String, action: () -> Unit) = JButton(LineIcon(icon)).apply {
        toolTipText = tooltip
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        addActionListener { action() }
    }

    fun button(icon: LineIcon.Kind?, text: String, action: () -> Unit) = JButton(text, icon?.let { LineIcon(it) }).apply {
        isFocusable = false
        addActionListener { action() }
    }

    fun label(text: String, size: Float? = null, bold: Boolean = false, muted: Boolean = false) = JLabel(text).apply {
        var f = font
        if (size != null) f = f.deriveFont(size)
        if (bold) f = f.deriveFont(Font.BOLD)
        font = f
        if (muted) foreground = UIManager.getColor("Label.disabledForeground")
    }

    fun wrapText(text: String, muted: Boolean = false, mono: Boolean = false) = JTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
        font = if (mono) Font(Font.MONOSPACED, Font.PLAIN, 12) else UIManager.getFont("Label.font")
        if (muted) foreground = UIManager.getColor("Label.disabledForeground")
        alignmentX = Component.LEFT_ALIGNMENT
    }

    fun help(text: String): JComponent = HelpIcon(text)

    fun row(vararg c: JComponent, gap: Int = 8) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        c.forEachIndexed { i, comp ->
            if (i > 0) add(Box.createHorizontalStrut(gap))
            comp.alignmentY = Component.CENTER_ALIGNMENT
            add(comp)
        }
        add(Box.createHorizontalGlue())
    }

    fun vbox(pad: Int = 0) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        if (pad > 0) border = BorderFactory.createEmptyBorder(pad, pad, pad, pad)
    }

    fun spinner() = javax.swing.JProgressBar().apply {
        isIndeterminate = true
        val d = Dimension(72, 6)
        preferredSize = d; maximumSize = d; minimumSize = d
    }

    fun loading(text: String) = row(spinner(), label(text, muted = true), gap = 8)

    fun gap(h: Int) = Box.createVerticalStrut(h)

    fun card() = vbox().apply {
        isOpaque = true
        background = UIManager.getColor("Panel.background").let { if (isDark()) it.brighter() else Color.WHITE }
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.LIGHT_GRAY, 1, true),
            BorderFactory.createEmptyBorder(16, 18, 16, 18),
        )
        alignmentX = Component.LEFT_ALIGNMENT
    }

    fun isDark() = UIManager.getColor("Panel.background")?.let { (it.red + it.green + it.blue) / 3 < 128 } ?: false

    fun collapsible(title: String, content: JComponent, expanded: Boolean = false): JPanel {
        val panel = vbox()
        val header = JLabel(title, LineIcon(if (expanded) LineIcon.Kind.CHEVRON_DOWN else LineIcon.Kind.CHEVRON_RIGHT, 14), JLabel.LEFT).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        content.isVisible = expanded
        content.alignmentX = Component.LEFT_ALIGNMENT
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                content.isVisible = !content.isVisible
                header.icon = LineIcon(if (content.isVisible) LineIcon.Kind.CHEVRON_DOWN else LineIcon.Kind.CHEVRON_RIGHT, 14)
                panel.revalidate(); panel.repaint()
            }
        })
        panel.add(header)
        panel.add(gap(6))
        panel.add(content)
        return panel
    }

    fun openFolder(f: File) {
        runCatching { Desktop.getDesktop().open(if (f.isDirectory) f else f.parentFile) }
    }

    fun confirm(owner: Component, text: String): Boolean =
        javax.swing.JOptionPane.showConfirmDialog(owner, text, "CrashHunter", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE) == javax.swing.JOptionPane.YES_OPTION

    fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    fun toast(owner: Component, text: String) {
        val win = SwingUtilities.getWindowAncestor(owner) ?: return
        val w = JWindow(win)
        val l = JLabel(text).apply {
            border = BorderFactory.createEmptyBorder(10, 16, 10, 16)
            foreground = Color.WHITE
        }
        w.contentPane = JPanel(BorderLayout()).apply { background = Color(0x32, 0x36, 0x3B); add(l) }
        w.pack()
        w.setLocation(win.x + win.width - w.width - 24, win.y + win.height - w.height - 24)
        w.isVisible = true
        Timer(2500) { w.dispose() }.apply { isRepeats = false; start() }
    }

    fun fixedHeight(c: JComponent): JComponent { c.maximumSize = Dimension(Int.MAX_VALUE, c.preferredSize.height); return c }
}

class BusyLabel(text: String = "", size: Float = 11f) : JPanel() {
    private val bar = Ui.spinner().apply { isVisible = false }
    private val gapBox = Box.createHorizontalStrut(6).apply { isVisible = false }
    val label = Ui.label(text, size, muted = true)

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(bar); add(strut); add(label)
    }

    private val strut get() = gapBox

    fun busy(text: String) { bar.isVisible = true; gapBox.isVisible = true; label.text = text; revalidate(); repaint() }
    fun done(text: String) { bar.isVisible = false; gapBox.isVisible = false; label.text = text; revalidate(); repaint() }
}

object EscapeCloser {
    const val HANDLER = "crashhunter.escape"

    fun install() {
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED || e.keyCode != java.awt.event.KeyEvent.VK_ESCAPE) return@addKeyEventDispatcher false
            val w = (e.component as? java.awt.Window) ?: e.component?.let { SwingUtilities.getWindowAncestor(it) } ?: return@addKeyEventDispatcher false
            if (w !is javax.swing.JDialog) return@addKeyEventDispatcher false
            @Suppress("UNCHECKED_CAST")
            val handler = w.rootPane.getClientProperty(HANDLER) as? () -> Unit
            if (handler != null) handler() else w.dispose()
            true
        }
    }

    fun onEscape(dialog: javax.swing.JDialog, handler: () -> Unit) = dialog.rootPane.putClientProperty(HANDLER, handler)
}

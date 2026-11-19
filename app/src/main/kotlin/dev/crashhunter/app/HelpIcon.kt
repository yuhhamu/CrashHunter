package dev.crashhunter.app

import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JToolTip
import javax.swing.Popup
import javax.swing.PopupFactory
import javax.swing.Timer
import javax.swing.UIManager

class HelpIcon(private val text: String) : JLabel(LineIcon(LineIcon.Kind.HELP, 14)) {
    private var popup: Popup? = null
    private var tip: JToolTip? = null
    private var pinned = false
    private val openLater = Timer(150) { open() }.apply { isRepeats = false }
    private val watch = Timer(300) { if (!pinned && !hovered()) close() }
    private val outside = AWTEventListener { e ->
        if (e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED && pinned && !inside(e.locationOnScreen)) close()
    }

    init {
        foreground = UIManager.getColor("Label.disabledForeground")
        border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        getAccessibleContext().accessibleDescription = text
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { openLater.restart() }
            override fun mouseExited(e: MouseEvent) { openLater.stop() }
            override fun mousePressed(e: MouseEvent) {
                openLater.stop()
                if (pinned) close() else { open(); pinned = true; Toolkit.getDefaultToolkit().addAWTEventListener(outside, AWTEvent.MOUSE_EVENT_MASK) }
            }
        })
    }

    private fun inside(p: Point): Boolean =
        (isShowing && Rectangle(locationOnScreen, size).contains(p)) || tip?.let { it.isShowing && Rectangle(it.locationOnScreen, it.size).contains(p) } == true

    private fun hovered(): Boolean = MouseInfo.getPointerInfo()?.location?.let(::inside) ?: false

    private fun open() {
        if (popup != null || !isShowing) return
        val t = createToolTip().apply { tipText = "<html><div style='width:280px'>$text</div></html>" }
        val at = locationOnScreen
        val size = t.preferredSize
        val screen = graphicsConfiguration?.bounds ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        var x = at.x
        var y = at.y + height
        if (x + size.width > screen.x + screen.width) x = screen.x + screen.width - size.width
        if (y + size.height > screen.y + screen.height) y = at.y - size.height
        popup = PopupFactory.getSharedInstance().getPopup(this, t, x, y).also { it.show() }
        tip = t
        watch.start()
    }

    fun close() {
        popup?.hide()
        popup = null
        tip = null
        pinned = false
        watch.stop()
        Toolkit.getDefaultToolkit().removeAWTEventListener(outside)
    }

    override fun removeNotify() {
        close()
        super.removeNotify()
    }
}

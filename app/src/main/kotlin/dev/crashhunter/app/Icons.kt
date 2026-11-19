package dev.crashhunter.app

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.UIManager

class LineIcon(private val kind: Kind, private val size: Int = 16, private val tint: Color? = null) : Icon {
    enum class Kind { SEARCH, BISECT, GEAR, REFRESH, FOLDER, COPY, PLAY, STOP, TRASH, HELP, CHEVRON_RIGHT, CHEVRON_DOWN, FILE, TUNE, CLOSE }

    override fun getIconWidth() = size
    override fun getIconHeight() = size

    override fun paintIcon(c: Component?, g0: Graphics, x: Int, y: Int) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.translate(x, y)
        val s = size / 16.0
        g.scale(s, s)
        val enabled = c?.isEnabled ?: true
        g.color = tint ?: (if (enabled) c?.foreground else UIManager.getColor("Label.disabledForeground")) ?: Color.GRAY
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (kind) {
            Kind.SEARCH -> { g.draw(Ellipse2D.Double(2.5, 2.5, 8.0, 8.0)); g.draw(Line2D.Double(9.5, 9.5, 13.5, 13.5)) }
            Kind.BISECT -> {
                g.draw(Line2D.Double(8.0, 2.0, 8.0, 6.5))
                val p = Path2D.Double(); p.moveTo(3.0, 13.5); p.lineTo(3.0, 9.0); p.lineTo(13.0, 9.0); p.lineTo(13.0, 13.5); g.draw(p)
                g.draw(Line2D.Double(8.0, 6.5, 8.0, 9.0))
                g.fill(Ellipse2D.Double(1.5, 12.0, 3.0, 3.0)); g.fill(Ellipse2D.Double(11.5, 12.0, 3.0, 3.0))
            }
            Kind.GEAR -> {
                g.draw(Ellipse2D.Double(5.0, 5.0, 6.0, 6.0))
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    g.draw(Line2D.Double(8 + Math.cos(a) * 4.5, 8 + Math.sin(a) * 4.5, 8 + Math.cos(a) * 6.5, 8 + Math.sin(a) * 6.5))
                }
            }
            Kind.REFRESH -> {
                g.draw(Arc2D.Double(2.5, 2.5, 11.0, 11.0, 40.0, 280.0, Arc2D.OPEN))
                val p = Path2D.Double(); p.moveTo(12.5, 1.5); p.lineTo(12.8, 5.0); p.lineTo(9.3, 5.0); g.draw(p)
            }
            Kind.FOLDER -> {
                val p = Path2D.Double(); p.moveTo(1.5, 4.0); p.lineTo(6.0, 4.0); p.lineTo(7.5, 5.5); p.lineTo(14.5, 5.5); p.lineTo(14.5, 13.0); p.lineTo(1.5, 13.0); p.closePath(); g.draw(p)
            }
            Kind.COPY -> { g.draw(RoundRectangle2D.Double(5.0, 5.0, 9.0, 9.0, 2.0, 2.0)); val p = Path2D.Double(); p.moveTo(3.0, 11.0); p.lineTo(2.0, 11.0); p.lineTo(2.0, 2.0); p.lineTo(11.0, 2.0); p.lineTo(11.0, 3.0); g.draw(p) }
            Kind.PLAY -> { val p = Path2D.Double(); p.moveTo(4.5, 2.5); p.lineTo(13.0, 8.0); p.lineTo(4.5, 13.5); p.closePath(); g.fill(p) }
            Kind.CLOSE -> { g.draw(Line2D.Double(4.0, 4.0, 12.0, 12.0)); g.draw(Line2D.Double(12.0, 4.0, 4.0, 12.0)) }
            Kind.STOP -> g.fill(RoundRectangle2D.Double(3.5, 3.5, 9.0, 9.0, 2.0, 2.0))
            Kind.TRASH -> {
                g.draw(Line2D.Double(2.5, 4.0, 13.5, 4.0)); g.draw(Line2D.Double(6.0, 2.0, 10.0, 2.0))
                val p = Path2D.Double(); p.moveTo(4.0, 4.0); p.lineTo(5.0, 14.0); p.lineTo(11.0, 14.0); p.lineTo(12.0, 4.0); g.draw(p)
            }
            Kind.HELP -> {
                g.draw(Ellipse2D.Double(1.5, 1.5, 13.0, 13.0))
                g.draw(Arc2D.Double(5.5, 4.0, 5.0, 4.5, 180.0, -230.0, Arc2D.OPEN)); g.draw(Line2D.Double(8.0, 8.8, 8.0, 9.8))
                g.fill(Ellipse2D.Double(7.2, 11.2, 1.6, 1.6))
            }
            Kind.CHEVRON_RIGHT -> { val p = Path2D.Double(); p.moveTo(6.0, 3.5); p.lineTo(10.5, 8.0); p.lineTo(6.0, 12.5); g.draw(p) }
            Kind.CHEVRON_DOWN -> { val p = Path2D.Double(); p.moveTo(3.5, 6.0); p.lineTo(8.0, 10.5); p.lineTo(12.5, 6.0); g.draw(p) }
            Kind.TUNE -> {
                for ((y, k) in listOf(4.0 to 5.0, 8.0 to 10.5, 12.0 to 7.0)) {
                    g.draw(Line2D.Double(2.0, y, 14.0, y))
                    g.fill(Ellipse2D.Double(k - 1.8, y - 1.8, 3.6, 3.6))
                }
            }
            Kind.FILE -> { val p = Path2D.Double(); p.moveTo(3.5, 1.5); p.lineTo(9.5, 1.5); p.lineTo(12.5, 4.5); p.lineTo(12.5, 14.5); p.lineTo(3.5, 14.5); p.closePath(); g.draw(p) }
        }
        g.dispose()
    }
}

class DotIcon(private val color: Color, private val size: Int = 10) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g0: Graphics, x: Int, y: Int) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        g.fill(Ellipse2D.Double(x.toDouble(), y.toDouble(), size.toDouble(), size.toDouble()))
        g.dispose()
    }
}

object StatusColors {
    val error = Color(0xD9, 0x3F, 0x3F)
    val warn = Color(0xE0, 0x9A, 0x2B)
    val ok = Color(0x3A, 0xA6, 0x5B)
    val neutral = Color(0x9A, 0xA0, 0xA6)
    val running = Color(0x3B, 0x82, 0xF6)
}

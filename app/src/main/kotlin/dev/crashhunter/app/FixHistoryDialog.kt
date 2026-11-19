package dev.crashhunter.app

import dev.crashhunter.core.fix.FixState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

object FixHistoryDialog {
    fun show(ctx: AppContext, owner: Component) {
        val dlg = JDialog(SwingUtilities.getWindowAncestor(owner), ctx.strings["settings.history"])
        val body = Ui.vbox(12)
        fun fill() {
            body.removeAll()
            val list = ctx.fixApplier().history().take(50)
            if (list.isEmpty()) body.add(Ui.label(ctx.strings["fix.history.empty"], muted = true))
            val fmt = SimpleDateFormat("MM/dd HH:mm")
            for (r in list) {
                val name = ctx.instances.firstOrNull { it.id == r.instanceId }?.name ?: r.instanceId
                val what = r.proposals.joinToString(" / ") { FixCard.text(ctx, it) }
                val row = Ui.vbox().apply { border = BorderFactory.createEmptyBorder(4, 0, 6, 0); alignmentX = Component.LEFT_ALIGNMENT }
                row.add(Ui.label("${fmt.format(Date(r.time))}  $name  ·  ${ctx.strings["fix.state.${r.state.name}"]}", 11f, muted = true))
                row.add(Ui.wrapText(what))
                if (r.state != FixState.UNDONE && r.state != FixState.FAILED) {
                    row.add(Ui.row(Ui.button(null, ctx.strings["fix.undo"]) {
                        if (Ui.confirm(dlg, ctx.strings["fix.undo.confirm"])) { ctx.fixApplier().undo(r); fill() }
                    }))
                }
                body.add(row)
            }
            body.revalidate(); body.repaint()
        }
        fill()
        dlg.contentPane = JPanel(BorderLayout()).apply { add(JScrollPane(JPanel(BorderLayout()).apply { add(body, BorderLayout.NORTH) }).apply { border = null }) }
        dlg.size = Dimension(640, 480)
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }
}

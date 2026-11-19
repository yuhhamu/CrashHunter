package dev.crashhunter.app

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

object Links {
    const val REPO = "https://github.com/yuhhamu/CrashHunter"
    const val SPONSORS = "https://github.com/sponsors/yuhhamu"
    const val X = "https://x.com/yuhhamu"
    const val X_NAME = "@yuhhamu"
    const val PRIVACY = "https://github.com/yuhhamu/CrashHunter/blob/main/docs/database-privacy.md"
    const val CF_CONSOLE = "https://console.curseforge.com/"

    fun open(url: String) { runCatching { Desktop.getDesktop().browse(URI(url)) } }

    fun label(text: String, url: String) = JLabel("<html><a href=''>$text</a></html>").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = url
        alignmentX = Component.LEFT_ALIGNMENT
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = open(url) })
    }
}

object Onboarding {
    const val TERMS_VERSION = 3

    fun resource(name: String): String =
        Onboarding::class.java.getResourceAsStream("/crashhunter/$name")?.use { it.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF") } ?: ""

    fun terms(lang: String): String = resource(if (lang == "ja") "terms_ja.md" else "terms_en.md").ifBlank { resource("terms_en.md") }

    fun run(frame: MainFrame, ctx: AppContext) {
        if (ctx.settings.termsVersion < TERMS_VERSION) {
            if (!showTerms(frame, ctx, requireAccept = true)) { frame.dispose(); kotlin.system.exitProcess(0) }
            ctx.update { it.copy(termsVersion = TERMS_VERSION) }
        }
        if (!ctx.settings.onboarded) {
            askData(frame, ctx)
            showSponsor(frame, ctx)
            ctx.update { it.copy(onboarded = true) }
        }
    }

    fun showTerms(owner: Component, ctx: AppContext, requireAccept: Boolean): Boolean {
        val s = ctx.strings
        var accepted = false
        val d = dialog(owner, s["terms.title"])
        val area = MarkdownView(terms(ctx.settings.lang))
        val buttons = if (requireAccept) {
            val accept = JButton(s["terms.accept"]).apply { addActionListener { accepted = true; d.dispose() } }
            d.rootPane.defaultButton = accept
            listOf(JButton(s["terms.decline"]).apply { addActionListener { d.dispose() } }, accept)
        } else listOf(JButton(s["common.close"]).apply { addActionListener { d.dispose() } })
        if (requireAccept) EscapeCloser.onEscape(d) {}
        d.contentPane = body(scroll(area), buttons)
        show(d, owner)
        return accepted || !requireAccept
    }

    private fun askData(owner: Component, ctx: AppContext) {
        val s = ctx.strings
        val d = dialog(owner, s["onboard.data.title"])
        EscapeCloser.onEscape(d) {}
        val use = JCheckBox(s["settings.db.use"], ctx.settings.dbUse)
        val send = JCheckBox(s["settings.db.send"], ctx.settings.dbSend)
        val runs = JCheckBox(s["settings.db.runs"], ctx.settings.dbSendRuns)
        val box = Ui.vbox().apply { border = BorderFactory.createEmptyBorder(12, 14, 4, 14) }
        box.add(Ui.label(s["onboard.data.text"]))
        box.add(Ui.gap(10))
        for ((c, help) in listOf(use to s["settings.db.use.help"], send to s["settings.db.send.help"], runs to s["settings.db.runs.help"])) {
            box.add(Ui.row(c, Ui.help(help), gap = 4))
        }
        box.add(Ui.gap(8))
        box.add(Links.label(s["onboard.data.more"], Links.PRIVACY))
        val ok = JButton(s["onboard.data.ok"]).apply { addActionListener { d.dispose() } }
        d.rootPane.defaultButton = ok
        d.contentPane = body(box, listOf(ok))
        show(d, owner)
        ctx.update { it.copy(dbUse = use.isSelected, dbSend = send.isSelected, dbSendRuns = runs.isSelected) }
    }

    fun showSponsor(owner: Component, ctx: AppContext) {
        val s = ctx.strings
        val d = dialog(owner, s["onboard.sponsor.title"])
        val box = Ui.vbox().apply { border = BorderFactory.createEmptyBorder(14, 14, 6, 14) }
        box.add(Ui.label(s["onboard.sponsor.text"]))
        box.add(Ui.gap(8))
        box.add(Links.label("X ${Links.X_NAME}", Links.X))
        val open = JButton(s["onboard.sponsor.open"]).apply { addActionListener { Links.open(Links.SPONSORS); d.dispose() } }
        d.rootPane.defaultButton = open
        d.contentPane = body(box, listOf(JButton(s["common.close"]).apply { addActionListener { d.dispose() } }, open))
        show(d, owner)
    }

    fun showText(owner: Component, title: String, text: String, closeText: String) {
        val d = dialog(owner, title)
        d.contentPane = body(scroll(MarkdownView(text)), listOf(JButton(closeText).apply { addActionListener { d.dispose() } }))
        show(d, owner)
    }

    private fun scroll(view: MarkdownView) = JScrollPane(view).apply {
        preferredSize = Dimension(620, 480)
        
    }

    private fun dialog(owner: Component, title: String) =
        JDialog(SwingUtilities.getWindowAncestor(owner) ?: (owner as? java.awt.Window), title, java.awt.Dialog.ModalityType.APPLICATION_MODAL).apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            iconImages = AppIcon.images()
        }

    private fun body(center: JComponent, buttons: List<JButton>) = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(center, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply { buttons.forEach { add(it) } }, BorderLayout.SOUTH)
    }

    private fun show(d: JDialog, owner: Component) {
        d.pack()
        d.setLocationRelativeTo(SwingUtilities.getWindowAncestor(owner) ?: owner)
        d.isVisible = true
    }
}

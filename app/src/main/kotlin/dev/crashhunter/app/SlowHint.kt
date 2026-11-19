package dev.crashhunter.app

import javax.swing.BorderFactory

class SlowHint(private val ctx: AppContext, private val afterMs: Long = 10 * 60_000L) {
    private var dismissed = false
    val panel = Ui.row(
        Ui.label(ctx.strings["hint.slow"]),
        Ui.button(null, ctx.strings["hint.slow.use"]) { ctx.update { it.copy(dbUse = true) }; dismiss() },
        Ui.iconButton(LineIcon.Kind.CLOSE, ctx.strings["common.close"]) { dismiss() },
        gap = 8,
    ).apply {
        border = BorderFactory.createEmptyBorder(6, 0, 0, 0)
        isVisible = false
    }

    fun reset() { dismissed = false; panel.isVisible = false }

    fun tick(elapsedMs: Long) {
        val show = !dismissed && !ctx.settings.dbUse && elapsedMs >= afterMs
        if (panel.isVisible != show) { panel.isVisible = show; panel.revalidate(); panel.parent?.repaint() }
    }

    private fun dismiss() { dismissed = true; panel.isVisible = false; panel.revalidate(); panel.parent?.repaint() }
}

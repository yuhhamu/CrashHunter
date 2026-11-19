package dev.crashhunter.app

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.bisect.BisectReport
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.model.GameInstance
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

object ResultCards {
    private fun header(title: String, color: Color) = Ui.row(JLabel(DotIcon(color, 12)), Ui.label(title, 18f, bold = true), gap = 10)

    private fun culprit(ctx: AppContext, name: String, sub: String?): JComponent = Ui.vbox().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(Ui.label(ctx.strings["card.culprit"], 11f, muted = true))
        add(Ui.row(Ui.label(name, 16f, bold = true), *(if (sub != null) arrayOf(Ui.label(sub, muted = true)) else emptyArray()), gap = 8))
    }

    private fun solutions(ctx: AppContext, list: List<String>): JComponent = Ui.vbox().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(Ui.label(ctx.strings["card.solutions"], 11f, muted = true))
        add(Ui.gap(4))
        list.take(3).forEachIndexed { i, s -> add(Ui.wrapText("${i + 1}. $s")); add(Ui.gap(2)) }
    }

    fun diagnosis(ctx: AppContext, d: Diagnosis, instance: GameInstance?, withDetails: Boolean = true, onBisect: () -> Unit): JComponent {
        val card = Ui.card()
        val color = DiagnosisPanel.colorOf(d)
        card.add(header(d.title, color))
        card.add(Ui.gap(12))
        val top = d.topSuspect
        if (top != null && !d.cause.environmental) {
            card.add(culprit(ctx, top.name, top.jar?.takeIf { it != top.name }))
            card.add(Ui.gap(12))
        }
        card.add(Ui.wrapText(d.detail))
        if (d.solutions.isNotEmpty()) {
            card.add(Ui.gap(14))
            card.add(solutions(ctx, d.solutions))
        }
        card.add(Ui.gap(14))
        val buttons = mutableListOf<JComponent>()
        if (d.cause != Cause.NO_PROBLEM && d.source.kind != SourceKind.MODS_CHECK) {
            buttons += Ui.button(LineIcon.Kind.BISECT, ctx.strings["action.bisect"]) { onBisect() }
        }
        buttons += Ui.iconButton(LineIcon.Kind.COPY, ctx.strings["action.copy"]) {
            Ui.copy(DiagnosisPanel.report(ctx, d)); Ui.toast(card, ctx.strings["toast.copied"])
        }
        (d.source.file ?: instance?.gameDir)?.let { f -> buttons += Ui.iconButton(LineIcon.Kind.FOLDER, ctx.strings["action.openFolder"]) { Ui.openFolder(f) } }
        card.add(Ui.row(*buttons.toTypedArray()))
        if (withDetails) detailsBody(ctx, d)?.let { more ->
            card.add(Ui.gap(14))
            card.add(Ui.collapsible(ctx.strings["card.details"], more))
        }
        return wrap(card)
    }

    fun details(ctx: AppContext, d: Diagnosis): JComponent? {
        val more = detailsBody(ctx, d) ?: return null
        val card = Ui.card()
        card.add(Ui.collapsible(ctx.strings["card.details"], more))
        return wrap(card)
    }

    private fun detailsBody(ctx: AppContext, d: Diagnosis): JComponent? {
        val top = d.topSuspect
        val more = Ui.vbox()
        if (d.suspects.size > 1 || (top != null && d.cause.environmental)) {
            more.add(Ui.label(ctx.strings["card.otherSuspects"], 11f, muted = true))
            d.suspects.drop(if (d.cause.environmental) 0 else 1).take(5).forEach { s ->
                val why = s.evidence.map { ctx.strings["evidence.${it.kind.name}"] }.distinct().joinToString(", ")
                more.add(Ui.wrapText("・${s.name}${s.jar?.let { j -> "  ($j)" } ?: ""} — $why"))
            }
            more.add(Ui.gap(8))
        }
        if (top != null) {
            more.add(Ui.label(ctx.strings["card.evidence"], 11f, muted = true))
            top.evidence.take(4).forEach { more.add(Ui.wrapText("・${ctx.strings["evidence.${it.kind.name}"]}: ${it.detail}", muted = true)) }
            more.add(Ui.gap(8))
        }
        if (d.otherProblems.isNotEmpty()) {
            more.add(Ui.label(ctx.strings["card.otherProblems"], 11f, muted = true))
            d.otherProblems.forEach { more.add(Ui.wrapText("・$it")) }
            more.add(Ui.gap(8))
        }
        d.parsed?.excerpt?.takeIf { it.isNotBlank() }?.let {
            more.add(Ui.wrapText(it.lines().take(16).joinToString("\n"), mono = true))
        }
        d.source.file?.let { more.add(Ui.gap(6)); more.add(Ui.label(it.name, 11f, muted = true)) }
        return more.takeIf { it.componentCount > 0 }
    }

    fun bisect(ctx: AppContext, r: BisectReport, onDeleteCopy: (() -> Unit)?, onAgain: () -> Unit): JComponent {
        val card = Ui.card()
        val res = r.result
        val (title, color) = when (res.status) {
            BisectStatus.FOUND -> (if (r.culprits.size > 1) ctx.strings["bisect.foundPair"] else ctx.strings["bisect.found"]) to StatusColors.error
            BisectStatus.NOT_A_MOD -> ctx.strings["bisect.notMod"] to StatusColors.warn
            BisectStatus.NO_FAILURE_AT_BASELINE -> ctx.strings["bisect.noRepro"] to StatusColors.neutral
            BisectStatus.START_FAILED -> ctx.strings["bisect.startFailed"] to StatusColors.warn
            BisectStatus.INCONCLUSIVE -> ctx.strings["bisect.inconclusive"] to StatusColors.warn
            BisectStatus.CANCELLED -> ctx.strings["bisect.cancelled"] to StatusColors.neutral
            BisectStatus.ERROR -> ctx.strings["bisect.error"] to StatusColors.warn
        }
        card.add(header(title, color))
        card.add(Ui.gap(12))
        if (r.culprits.isNotEmpty()) {
            val names = r.culprits.joinToString(" + ") { it.displayName }
            card.add(culprit(ctx, names, r.culprits.joinToString(", ") { it.fileName }.takeIf { it != names }))
            card.add(Ui.gap(10))
        }
        val detail = when (res.status) {
            BisectStatus.FOUND -> buildString {
                append(ctx.strings.f(if (r.culprits.size > 1) "bisect.foundPair.detail" else "bisect.found.detail", res.trials))
                if (r.requirements.isNotEmpty()) append("\n").append(ctx.strings.f("bisect.requirements", r.requirements.joinToString(", ") { it.displayName }))
                r.crashDiagnosis?.takeIf { it.cause != Cause.UNKNOWN }?.let { append("\n").append(it.title).append(": ").append(it.detail) }
                if (res.otherCrashSeen) append("\n").append(ctx.strings["bisect.otherCrash"])
            }
            BisectStatus.START_FAILED -> if (res.message == dev.crashhunter.core.bisect.PRISM_UNRESPONSIVE) ctx.strings["bisect.prismUnresponsive"]
                else ctx.strings["bisect.startFailed.detail"] + (res.message.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            BisectStatus.INCONCLUSIVE -> ctx.strings["bisect.inconclusive.detail"]
            BisectStatus.NOT_A_MOD -> ctx.strings["bisect.notMod.detail"]
            BisectStatus.NO_FAILURE_AT_BASELINE -> ctx.strings["bisect.noRepro.detail"]
            BisectStatus.ERROR -> res.message
            BisectStatus.CANCELLED -> ctx.strings.f("bisect.cancelled.detail", res.trials)
        }
        card.add(Ui.wrapText(detail))
        if (res.status == BisectStatus.FOUND) {
            card.add(Ui.gap(14))
            val names = r.culprits.joinToString(" / ") { it.displayName }
            val sols = r.crashDiagnosis?.solutionsAfterBisect?.takeIf { it.isNotEmpty() && r.culprits.size == 1 }
                ?.let { if (it.size < 3) it + ctx.strings.f("bisect.sol.report", names) else it }
                ?: listOf(ctx.strings.f("bisect.sol.update", names), ctx.strings.f("bisect.sol.remove", names), ctx.strings.f("bisect.sol.report", names))
            card.add(solutions(ctx, sols))
        }
        card.add(Ui.gap(14))
        val buttons = mutableListOf<JComponent>(Ui.button(LineIcon.Kind.PLAY, ctx.strings["action.again"]) { onAgain() })
        buttons += Ui.iconButton(LineIcon.Kind.FOLDER, ctx.strings["action.openFolder"]) { Ui.openFolder(r.target.modsDir) }
        if (onDeleteCopy != null) buttons += Ui.iconButton(LineIcon.Kind.TRASH, ctx.strings["action.deleteCopy"]) { onDeleteCopy() }
        card.add(Ui.row(*buttons.toTypedArray()))
        return wrap(card)
    }

    private fun wrap(card: JComponent): JComponent = JPanel(BorderLayout()).apply { isOpaque = false; add(card, BorderLayout.NORTH) }
}

object EmptyState {
    fun create(ctx: AppContext, frame: MainFrame): JComponent {
        val p = Ui.vbox()
        val text = if (ctx.install == null) ctx.strings["empty.noPrism"] else ctx.strings["empty.noInstance"]
        p.add(Ui.label(text).apply { alignmentX = Component.CENTER_ALIGNMENT })
        p.add(Ui.gap(10))
        p.add(JButton(ctx.strings["empty.choosePrism"]).apply { alignmentX = Component.CENTER_ALIGNMENT; addActionListener { frame.showPage("settings") } })
        return JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; add(p) }
    }
}


package dev.crashhunter.app

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.fix.FixOutcome
import dev.crashhunter.core.fix.FixProposal
import dev.crashhunter.core.fix.FixRecord
import dev.crashhunter.core.fix.VerifyResult
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.net.URI
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class FixCard(
    private val ctx: AppContext,
    private val instance: GameInstance,
    private val diagnoses: List<Diagnosis>,
    private val culprits: List<String> = emptyList(),
    private val before: CrashFingerprint? = diagnoses.firstOrNull()?.fingerprint,
    private val onFinished: (FixOutcome) -> Unit = {},
) : JPanel(BorderLayout()) {
    private val scope = MainScope()
    private val card = Ui.card()
    private val checks = mutableListOf<Pair<JCheckBox, FixProposal>>()
    private val status = Ui.label("", muted = true)
    private var lastRecords: List<FixRecord> = emptyList()

    init {
        isOpaque = false
        add(card, BorderLayout.NORTH)
        card.add(Ui.loading(ctx.strings["fix.loading"]))
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { ctx.fixPlanner().plan(instance, ModIndex.load(instance.modsDir), diagnoses, culprits) }.getOrDefault(emptyList())
            }
            show(list)
        }
    }

    private fun show(list: List<FixProposal>) {
        card.removeAll()
        checks.clear()
        val previous = ctx.lastFix[instance.id]
        if (list.isEmpty() && previous == null) { isVisible = false; return }
        card.add(Ui.row(Ui.label(ctx.strings["fix.title"], bold = true), Ui.help(ctx.strings["fix.help"])))
        card.add(Ui.gap(8))
        if (previous != null) {
            lastRecords = previous.records
            val parts = mutableListOf<JComponent>(Ui.label(ctx.strings["fix.last"] + ": " + resultText(ctx, previous), muted = true))
            if (previous.records.any { it.state != dev.crashhunter.core.fix.FixState.UNDONE }) parts += Ui.button(null, ctx.strings["fix.undo"]) { undo() }
            card.add(Ui.row(*parts.toTypedArray()))
            card.add(Ui.gap(8))
        }
        for (p in list) {
            val text = text(ctx, p)
            if (p.actions.isEmpty()) {
                val row = mutableListOf<JComponent>(Ui.wrapText(text))
                p.manualUrl?.let { url -> row += Ui.iconButton(LineIcon.Kind.FILE, ctx.strings["fix.openPage"]) { browse(url) } }
                card.add(Ui.row(*row.toTypedArray()))
            } else {
                val cb = JCheckBox("<html><body style='width:460px'>" + MainFrame.esc(text) + "</body></html>", true).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
                checks += cb to p
                card.add(cb)
            }
            card.add(Ui.gap(2))
        }
        card.add(Ui.gap(10))
        val buttons = mutableListOf<JComponent>()
        if (checks.isNotEmpty()) buttons += Ui.button(LineIcon.Kind.PLAY, ctx.strings["fix.apply"]) { applySelected() }
        if (buttons.isNotEmpty()) card.add(Ui.row(*buttons.toTypedArray(), status))
        isVisible = true
        revalidate(); repaint()
    }

    private fun applySelected() {
        val chosen = checks.filter { it.first.isSelected }.map { it.second }
        if (chosen.isEmpty() || ctx.fixRunning) return
        if (chosen.any { it.kind == FixKind.CULPRIT } && !Ui.confirm(this, ctx.strings["fix.culprit.confirm"])) return
        val unhashed = chosen.flatMap { p -> p.actions.flatMap { a -> (a as? dev.crashhunter.core.fix.AddMod)?.candidates ?: (a as? dev.crashhunter.core.fix.ReplaceMod)?.candidates ?: emptyList() } }
            .filter { it.sha1 == null && it.sha512 == null }
        val allowUnhashed = unhashed.isNotEmpty() && Ui.confirm(this, ctx.strings.f("fix.unhashed.confirm", unhashed.joinToString(", ") { it.fileName }))
        checks.forEach { it.first.isEnabled = false }
        ctx.fixRunning = true
        status.text = ctx.strings["fix.state.apply"]
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.runFix(instance, chosen, before, allowUnhashed) { s -> SwingUtilities.invokeLater { status.text = stateText(s) ?: status.text } }
                }
            }
            ctx.fixRunning = false
            result.onSuccess { out ->
                ctx.lastFix[instance.id] = out
                Ui.toast(this@FixCard, resultText(ctx, out))
                TrayNotifier.notify("CrashHunter", ctx.strings.t("fix.notify.applied", mapOf("instance" to instance.name, "result" to resultText(ctx, out)))) {
                    (SwingUtilities.getWindowAncestor(this@FixCard) as? MainFrame)?.bringToFront()
                }
                lastRecords = out.records
                status.text = resultText(ctx, out)
                if (out.verify != VerifyResult.SAME_FAILURE && out.verify != VerifyResult.NOT_STARTED) {
                    card.add(Ui.gap(6))
                    card.add(Ui.row(Ui.button(null, ctx.strings["fix.undo"]) { undo() }))
                    card.revalidate()
                }
                DbRecorder(ctx).submit(DbRecorder(ctx).fromFix(instance, out, diagnoses.firstOrNull()), this@FixCard)
                onFinished(out)
            }.onFailure { e ->
                status.text = ctx.strings.t("fix.result.ERROR", mapOf("0" to (e.message ?: e.javaClass.simpleName)))
                checks.forEach { it.first.isEnabled = true }
            }
        }
    }

    private fun undo() {
        val rs = lastRecords.takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            withContext(Dispatchers.IO) { ctx.fixApplier().undoAll(rs) }
            ctx.lastFix.remove(instance.id)
            status.text = ctx.strings["fix.undone"]
            Ui.toast(this@FixCard, ctx.strings["fix.undone"])
        }
    }

    private fun stateText(s: String): String? {
        if (s.startsWith("round:")) return ctx.strings.t("fix.state.round", mapOf("n" to s.substringAfter(':')))
        return ctx.strings["fix.state.$s"].takeIf { ctx.strings.has("fix.state.$s") }
    }

    private fun browse(url: String) = runCatching { Desktop.getDesktop().browse(URI(url)) }

    companion object {
        fun text(ctx: AppContext, p: FixProposal): String {
            val key = when {
                p.kind == FixKind.JAVA && p.params["path"].isNullOrBlank() -> "fix.JAVA.auto"
                p.actions.isEmpty() -> "fix.${p.kind.name}.manual"
                else -> "fix.${p.kind.name}"
            }
            val reason = p.params["reason"]?.takeIf { it.isNotBlank() && ctx.strings.has("fix.reason.$it") }?.let { " (" + ctx.strings["fix.reason.$it"] + ")" } ?: ""
            val off = if (p.kind == FixKind.ENABLE_PROVIDER && !p.params["off"].isNullOrBlank()) ctx.strings.t("fix.ENABLE_PROVIDER.off", p.params) else ""
            val db = if (p.params["db"] == "1" && p.kind != FixKind.DB_FIX) " " + ctx.strings["fix.fromDb"] else ""
            return ctx.strings.t(key, p.params) + off + reason + db
        }

        fun resultText(ctx: AppContext, out: FixOutcome): String {
            val base = ctx.strings["fix.result.${out.verify.name}"]
            return if (out.rounds > 1) base + " " + ctx.strings.t("fix.rounds", mapOf("n" to out.rounds.toString())) else base
        }
    }
}

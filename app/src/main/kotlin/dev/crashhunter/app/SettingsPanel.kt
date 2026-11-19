package dev.crashhunter.app

import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.util.AppDirs
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class SettingsPanel(private val ctx: AppContext, private val frame: MainFrame) : JPanel(BorderLayout()) {
    private val foundLabel = Ui.label("", 11f, muted = true)
    private fun foundText() = ctx.install?.let { ctx.strings.f("settings.found", it.instancesDir.absolutePath, ctx.instances.size) } ?: ctx.strings["empty.noPrism"]

    init {
        build()
        frame.onInstanceChange { foundLabel.text = foundText() }
    }

    private fun build() {
        removeAll()
        val s = ctx.strings
        val st = ctx.settings
        val card = Ui.card()

        card.add(Ui.label(s["settings.prism"], 11f, muted = true))
        val exeField = JTextField(st.prismExe ?: ctx.install?.exe?.absolutePath ?: "").apply { isEditable = false }
        card.add(Ui.fixedHeight(JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            add(exeField)
            add(Ui.iconButton(LineIcon.Kind.FOLDER, s["settings.browse"]) {
                val ch = JFileChooser(ctx.install?.exe?.parentFile)
                ch.dialogTitle = s["settings.prism"]
                if (ch.showOpenDialog(this@SettingsPanel) == JFileChooser.APPROVE_OPTION) {
                    ctx.update { it.copy(prismExe = ch.selectedFile.absolutePath, prismDataDir = null) }
                    build(); Ui.toast(this@SettingsPanel, s["toast.saved"])
                }
            }, BorderLayout.EAST)
        }))
        card.add(Ui.gap(4))
        card.add(foundLabel.apply { text = foundText() })
        card.add(Ui.gap(14))

        card.add(Ui.label(s["settings.language"], 11f, muted = true))
        val langs = arrayOf(s["settings.lang.auto"], "日本語", "English")
        val lang = JComboBox(langs).apply {
            selectedIndex = when (st.language) { "ja" -> 1; "en" -> 2; else -> 0 }
            maximumSize = Dimension(200, preferredSize.height)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        lang.addActionListener {
            val v = when (lang.selectedIndex) { 1 -> "ja"; 2 -> "en"; else -> null }
            if (v != ctx.settings.language) {
                ctx.update { it.copy(language = v) }
                SwingUtilities.invokeLater { frame.dispose(); MainFrame(ctx).apply { isVisible = true; showPage("settings") } }
            }
        }
        card.add(lang)
        card.add(Ui.gap(14))

        val more = Ui.vbox()
        more.add(spin(s["settings.startTimeout"], st.startTimeoutSec, 30, 1800) { v -> ctx.update { it.copy(startTimeoutSec = v) } })
        more.add(spin(s["settings.timeout"], st.timeoutSec, 60, 7200) { v -> ctx.update { it.copy(timeoutSec = v) } })
        more.add(spin(s["settings.settle"], st.settleSec ?: 0, 0, 600, s["settings.settle.help"], auto = true) { v -> ctx.update { it.copy(settleSec = v.takeIf { it > 0 }) } })
        more.add(spin(s["settings.repeats"], st.repeats, 1, 5, s["settings.repeats.help"]) { v -> ctx.update { it.copy(repeats = v) } })
        more.add(spin(s["settings.parallel"], st.parallelTrials, 0, 4, s["settings.parallel.help"], auto = true) { v -> ctx.update { it.copy(parallelTrials = v) } })
        more.add(spin(s["settings.maxParallel"], st.maxParallel, 1, 4, s["settings.maxParallel.help"]) { v -> ctx.update { it.copy(maxParallel = v) } })
        more.add(check(s["settings.minimizeGames"], st.minimizeGames) { v -> ctx.update { it.copy(minimizeGames = v) } })
        more.add(check(s["settings.hang"], st.hangIsFailure) { v -> ctx.update { it.copy(hangIsFailure = v) } })
        more.add(check(s["settings.anyCrash"], st.anyCrash, s["settings.anyCrash.help"]) { v -> ctx.update { it.copy(anyCrash = v) } })
        more.add(check(s["settings.saves"], st.includeSaves, s["settings.saves.help"]) { v -> ctx.update { it.copy(includeSaves = v) } })
        more.add(Ui.gap(8))
        more.add(Ui.row(Ui.button(LineIcon.Kind.FOLDER, s["settings.dataDir"]) { Ui.openFolder(File(AppDirs.dataDir, ".")) }))
        card.add(Ui.collapsible(s["card.details"], more))
        card.add(Ui.gap(6))
        card.add(Ui.collapsible(s["settings.fixes"], fixSection()))
        card.add(Ui.gap(6))
        card.add(Ui.collapsible(s["settings.db"], dbSection()))
        card.add(Ui.gap(6))
        card.add(Ui.collapsible(s["settings.about"], aboutSection()))

        add(JScrollPane(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 16, 16, 16)
            add(card, BorderLayout.NORTH)
        }).apply { border = null }, BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun aboutSection(): JComponent {
        val s = ctx.strings
        val box = Ui.vbox()
        box.add(Ui.label("CrashHunter $VERSION_LABEL · ${s["settings.about.license"]}", 12f))
        box.add(Ui.gap(4))
        box.add(Ui.label(s["settings.about.disclaimer"], 12f))
        box.add(Ui.gap(4))
        box.add(Ui.row(Ui.label(s["settings.about.ai"], 12f), Ui.help(s["settings.about.ai.help"]), gap = 4))
        box.add(Ui.gap(6))
        box.add(Ui.row(
            Ui.button(null, s["terms.title"]) { Onboarding.showTerms(this, ctx, requireAccept = false) },
            Ui.button(null, s["settings.about.licenseText"]) { Onboarding.showText(this, s["settings.about.licenseText"], Onboarding.resource("LICENSE"), s["common.close"]) },
            Ui.button(null, s["settings.about.notice"]) { Onboarding.showText(this, s["settings.about.notice"], Onboarding.resource("NOTICE.md"), s["common.close"]) },
            gap = 6))
        box.add(Ui.gap(6))
        box.add(check(s["settings.about.checkUpdates"], ctx.settings.checkUpdates) { v -> ctx.update { it.copy(checkUpdates = v) } })
        box.add(Ui.gap(10))
        box.add(Ui.label(s["settings.about.author"], 11f, muted = true))
        box.add(Ui.gap(4))
        box.add(Links.label("X ${Links.X_NAME}", Links.X))
        box.add(Ui.gap(2))
        box.add(Links.label("GitHub Sponsors", Links.SPONSORS))
        box.add(Ui.gap(2))
        box.add(Links.label(s["settings.about.source"], Links.REPO))
        return box
    }

    private fun dbSection(): JComponent {
        val s = ctx.strings
        val st = ctx.settings
        val box = Ui.vbox()
        box.add(check(s["settings.db.use"], st.dbUse, s["settings.db.use.help"]) { v -> ctx.update { it.copy(dbUse = v) }; if (!v) ctx.crashDb.clearSnapshots() })
        val confirm = JCheckBox(s["settings.db.confirm"], st.dbConfirmSend).apply { isEnabled = st.dbSend; addActionListener { ctx.update { it.copy(dbConfirmSend = isSelected) } } }
        box.add(check(s["settings.db.send"], st.dbSend, s["settings.db.send.help"]) { v -> ctx.update { it.copy(dbSend = v) }; confirm.isEnabled = v })
        box.add(Ui.row(confirm).apply { border = BorderFactory.createEmptyBorder(0, 22, 0, 0) })
        box.add(check(s["settings.db.runs"], st.dbSendRuns, s["settings.db.runs.help"]) { v -> ctx.update { it.copy(dbSendRuns = v) } })
        box.add(Ui.gap(8))
        val pending = BusyLabel(s.t("settings.db.pending", mapOf("n" to ctx.crashDb.pending().size.toString())))
        if (ctx.crashDb.pending().isNotEmpty()) box.add(Ui.row(pending, Ui.button(null, s["settings.db.sendNow"]) {
            pending.busy(s["settings.db.sending"])
            Thread {
                val n = runCatching { kotlinx.coroutines.runBlocking { ctx.crashDb.flush() } }.getOrDefault(0)
                SwingUtilities.invokeLater {
                    pending.done(s.t("settings.db.pending", mapOf("n" to ctx.crashDb.pending().size.toString())))
                    Ui.toast(this, s.t("settings.db.sent", mapOf("n" to n.toString())))
                }
            }.start()
        }, gap = 6))
        box.add(Ui.gap(6))
        box.add(Links.label(s["onboard.data.more"], Links.PRIVACY))
        box.add(Ui.gap(6))
        box.add(Ui.row(
            Ui.button(null, s["settings.db.forget"]) {
                if (!Ui.confirm(this, s["settings.db.forget.confirm"])) return@button
                val id = ctx.installId()
                Thread {
                    val ok = runCatching { kotlinx.coroutines.runBlocking { ctx.crashDb.forgetMe(id) } }.getOrDefault(false)
                    SwingUtilities.invokeLater {
                        if (ok) ctx.resetInstallId()
                        Ui.toast(this, if (ok) s["settings.db.forget.done"] else s["settings.db.forget.failed"])
                    }
                }.start()
            }, gap = 6))
        return box
    }

    private fun fixSection(): JComponent {
        val s = ctx.strings
        val st = ctx.settings
        val box = Ui.vbox()
        fun kindCheck(k: FixKind): JComponent {
            val on = st.fixKinds[k.name] ?: k.defaultOn
            val help = if (s.has("settings.fix.${k.name}.help")) s["settings.fix.${k.name}.help"] else null
            return check(s["settings.fix.${k.name}"], on, help) { v -> ctx.update { it.copy(fixKinds = it.fixKinds + (k.name to v)) } }
        }
        box.add(Ui.label(s["settings.fixes.local"], 11f, muted = true))
        FixKind.entries.filter { !it.network }.forEach { box.add(kindCheck(it)) }
        box.add(Ui.gap(8))
        box.add(Ui.label(s["settings.fixes.network"], 11f, muted = true))
        FixKind.entries.filter { it.network }.forEach { box.add(kindCheck(it)) }
        box.add(Ui.gap(8))
        box.add(check(s["settings.autoApply"], st.autoApplyFixes, s["settings.autoApply.help"]) { v -> ctx.update { it.copy(autoApplyFixes = v) } })
        box.add(check(s["settings.verifyAfterFix"], st.verifyAfterFix) { v -> ctx.update { it.copy(verifyAfterFix = v) } })
        box.add(spin(s["settings.fixChain"], st.fixChainMax, 1, 20, s["settings.fixChain.help"]) { v -> ctx.update { it.copy(fixChainMax = v) } })
        box.add(check(s["settings.notifyFixes"], st.notifyFixes) { v -> ctx.update { it.copy(notifyFixes = v) } })
        box.add(Ui.gap(10))
        box.add(Ui.row(Ui.label(s["settings.cfKey"], 11f, muted = true), Ui.help(s["settings.cfKey.help"]), Links.label(s["settings.cfKey.get"], Links.CF_CONSOLE), gap = 4))
        val field = JPasswordField(24).apply { maximumSize = Dimension(320, preferredSize.height) }
        val state = BusyLabel(if (st.cfUserKey.isNullOrBlank()) s["settings.cfKey.none"] else s["settings.cfKey.set"])
        fun save(value: String?) {
            Thread {
                runCatching { ctx.setCfUserKey(value) }.onFailure { e -> SwingUtilities.invokeLater { Ui.toast(this, e.message ?: "error") } }
                SwingUtilities.invokeLater {
                    field.text = ""
                    state.done(if (ctx.settings.cfUserKey.isNullOrBlank()) s["settings.cfKey.none"] else s["settings.cfKey.set"])
                    Ui.toast(this, s["toast.saved"])
                }
            }.start()
        }
        fun check() {
            val typed = String(field.password)
            state.busy(s["settings.cfKey.checking"])
            Thread {
                val r = runCatching { kotlinx.coroutines.runBlocking { ctx.checkCfKey(typed) } }.getOrDefault(dev.crashhunter.core.fix.remote.KeyCheck.ERROR)
                SwingUtilities.invokeLater { state.done(s["settings.cfKey.check.${r.name}"]) }
            }.start()
        }
        box.add(Ui.row(field, Ui.button(null, s["settings.cfKey.save"]) { save(String(field.password)) }, Ui.button(null, s["settings.cfKey.check"]) { check() },
            Ui.iconButton(LineIcon.Kind.TRASH, s["settings.cfKey.clear"]) { save(null) }, state, gap = 6))
        box.add(Ui.gap(10))
        box.add(Ui.row(Ui.button(null, s["settings.history"]) { FixHistoryDialog.show(ctx, this) }))
        return box
    }

    private fun spin(label: String, value: Int, min: Int, max: Int, help: String? = null, auto: Boolean = false, set: (Int) -> Unit): JComponent {
        val sp = JSpinner(SpinnerNumberModel(value.coerceIn(min, max), min, max, 1)).apply { preferredSize = Dimension(90, preferredSize.height); maximumSize = preferredSize; minimumSize = preferredSize }
        if (auto) {
            val autoText = ctx.strings["settings.auto"]
            (sp.editor as JSpinner.DefaultEditor).textField.formatterFactory = javax.swing.text.DefaultFormatterFactory(object : javax.swing.text.NumberFormatter(java.text.NumberFormat.getIntegerInstance()) {
                override fun valueToString(v: Any?): String = if ((v as? Number)?.toInt() == 0) autoText else super.valueToString(v)
                override fun stringToValue(t: String?): Any = if (t == null || t.trim() == autoText || t.isBlank()) 0 else (super.stringToValue(t) as Number).toInt()
            })
        }
        sp.addChangeListener { set(sp.value as Int) }
        val name = Ui.label(label).apply { val d = Dimension(280, preferredSize.height); preferredSize = d; minimumSize = d; maximumSize = d }
        val parts = mutableListOf<JComponent>(name, sp)
        parts += help?.let { Ui.help(it) } ?: javax.swing.Box.createRigidArea(Dimension(24, 24)) as JComponent
        return Ui.row(*parts.toTypedArray(), gap = 6).also { it.border = BorderFactory.createEmptyBorder(2, 0, 2, 0) }
    }

    private fun check(label: String, value: Boolean, help: String? = null, set: (Boolean) -> Unit): JComponent {
        val cb = JCheckBox(label, value)
        cb.addActionListener { set(cb.isSelected) }
        return if (help != null) Ui.row(cb, Ui.help(help), gap = 4) else Ui.row(cb)
    }
}

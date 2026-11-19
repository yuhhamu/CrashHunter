package dev.crashhunter.app

import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.model.GameInstance
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

class MainFrame(private val ctx: AppContext) : JFrame("CrashHunter $VERSION_LABEL") {
    private val cards = CardLayout()
    private val pages = JPanel(cards)
    private val picker = InstancePicker(ctx)
    @Volatile private var lastSignature: Map<String, String>? = null
    private var scanning = false
    private val instanceListeners = mutableListOf<(GameInstance?) -> Unit>()
    private val pending = LinkedHashMap<String, Int>()
    private val crashWatcher = CrashWatcher(ctx, this)

    val diagnosisPage: DiagnosisPanel
    val bisectPage: BisectPanel
    val tunePage: TunePanel
    private val settingsPage: SettingsPanel
    private val navButtons = mutableListOf<JToggleButton>()

    val selectedInstance: GameInstance? get() = picker.selected

    fun onInstanceChange(l: (GameInstance?) -> Unit) { instanceListeners += l }

    private val updateBox = Ui.vbox().apply { isVisible = false; border = BorderFactory.createEmptyBorder(0, 8, 0, 0) }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        minimumSize = Dimension(920, 600)
        size = Dimension(1100, 720)
        setLocationRelativeTo(null)
        iconImages = AppIcon.images()

        diagnosisPage = DiagnosisPanel(ctx, this)
        bisectPage = BisectPanel(ctx, this)
        tunePage = TunePanel(ctx, this)
        settingsPage = SettingsPanel(ctx, this)
        pages.add(diagnosisPage, "diagnose")
        pages.add(bisectPage, "bisect")
        pages.add(tunePage, "tune")
        pages.add(settingsPage, "settings")

        picker.onSelect { sel ->
            sel?.let { i -> if (ctx.settings.lastInstance != i.id) ctx.update { it.copy(lastInstance = i.id) } }
            instanceListeners.forEach { it(sel) }
        }
        rootPane.registerKeyboardAction({ picker.openSearch() }, javax.swing.KeyStroke.getKeyStroke("ctrl F"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)

        contentPane = JPanel(BorderLayout()).apply {
            add(sidebar(), BorderLayout.WEST)
            add(JPanel(BorderLayout()).apply {
                add(topBar(), BorderLayout.NORTH)
                add(pages, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        fillInstances()
        ctx.onChange { rebuildTexts(); refreshInstances() }
        val watch = javax.swing.Timer(3000) { checkForChanges(); crashWatcher.tick() }.apply { start() }
        crashWatcher.tick()
        TrayNotifier.install { bringToFront(); showPage("diagnose") }
        DbRecorder(ctx).flushLater()
        UpdateCheck.start(ctx) { showUpdate(it) }
        onInstanceChange { inst -> refreshDb(inst) }
        refreshDb(selectedInstance)
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowActivated(e: java.awt.event.WindowEvent) = checkForChanges()
            override fun windowClosed(e: java.awt.event.WindowEvent) { watch.stop(); TrayNotifier.remove() }
        })
    }

    private fun checkForChanges() {
        if (scanning) return
        scanning = true
        Thread {
            val sig = runCatching { ctx.instanceSignatures() }.getOrNull()
            val old = lastSignature
            val changed = sig != null && old != null && sig != old
            val listChanged = changed && sig!!.keys != old!!.keys
            val selectedId = selectedInstance?.dir?.name
            val selectedChanged = changed && selectedId != null && sig!![selectedId] != old!![selectedId] && sig[selectedId] != "busy"
            if (sig != null) lastSignature = sig
            if (listChanged || selectedChanged) ctx.rescan()
            SwingUtilities.invokeLater {
                scanning = false
                if (listChanged || selectedChanged) refreshInstances()
            }
        }.apply { isDaemon = true; name = "instance-watch" }.start()
    }

    private fun sidebar(): JPanel {
        val p = Ui.vbox().apply {
            isOpaque = true
            background = UIManager.getColor("Panel.background").let { if (Ui.isDark()) it.darker() else java.awt.Color(0xF2, 0xF3, 0xF5) }
            border = BorderFactory.createEmptyBorder(14, 8, 14, 8)
            preferredSize = Dimension(150, 0)
        }
        p.add(JLabel("CrashHunter").apply { font = font.deriveFont(Font.BOLD, 15f); border = BorderFactory.createEmptyBorder(0, 8, 16, 0) })
        val group = ButtonGroup()
        listOf(Triple("diagnose", LineIcon.Kind.SEARCH, "nav.diagnose"), Triple("bisect", LineIcon.Kind.BISECT, "nav.bisect"), Triple("tune", LineIcon.Kind.TUNE, "nav.tune"), Triple("settings", LineIcon.Kind.GEAR, "nav.settings"))
            .forEach { (card, icon, key) ->
                val b = JToggleButton(ctx.strings[key], LineIcon(icon)).apply {
                    putClientProperty("JButton.buttonType", "toolBarButton")
                    horizontalAlignment = SwingConstants.LEFT
                    iconTextGap = 10
                    isFocusable = false
                    maximumSize = Dimension(Int.MAX_VALUE, 36)
                    alignmentX = Component.LEFT_ALIGNMENT
                    putClientProperty("navKey", key)
                    addActionListener { cards.show(pages, card) }
                }
                group.add(b); navButtons += b; p.add(b); p.add(Ui.gap(4))
            }
        navButtons.first().isSelected = true
        p.add(javax.swing.Box.createVerticalGlue())
        p.add(updateBox)
        return p
    }

    private fun showUpdate(r: UpdateCheck.Release) {
        val s = ctx.strings
        updateBox.removeAll()
        updateBox.add(Links.label(s.f("update.available", r.version), r.url))
        if (Updater.installed() && "CrashHunter-Setup-${r.version}.exe" in r.assets) {
            val state = BusyLabel("")
            lateinit var button: javax.swing.JButton
            button = Ui.button(null, s["update.install"]) {
                if (ctx.bisectRunning || ctx.fixRunning) { Ui.toast(this, s["update.busy"]); return@button }
                button.isEnabled = false
                state.busy(s["update.downloading"])
                Thread {
                    val result = runCatching { kotlinx.coroutines.runBlocking { Updater.download(ctx, r) } }
                    SwingUtilities.invokeLater {
                        result.onSuccess { setup ->
                            if (ctx.bisectRunning || ctx.fixRunning) { state.done(""); button.isEnabled = true; Ui.toast(this, s["update.busy"]); return@onSuccess }
                            state.busy(s["update.installing"])
                            runCatching { Updater.install(setup) }
                                .onSuccess { javax.swing.Timer(800) { System.exit(0) }.apply { isRepeats = false }.start() }
                                .onFailure { e -> state.done(""); button.isEnabled = true; Ui.toast(this, s.f("update.failed", e.message ?: e.javaClass.simpleName)) }
                        }.onFailure { e -> state.done(""); button.isEnabled = true; Ui.toast(this, s.f("update.failed", e.message ?: e.javaClass.simpleName)) }
                    }
                }.apply { isDaemon = true; name = "update-download" }.start()
            }
            updateBox.add(Ui.gap(4))
            updateBox.add(Ui.row(button, gap = 0))
            updateBox.add(state)
        }
        updateBox.isVisible = true
        updateBox.revalidate()
        TrayNotifier.notify("CrashHunter", ctx.strings.f("update.available", r.version)) { Links.open(r.url) }
    }

    private fun topBar() = JPanel(BorderLayout(8, 0)).apply {
        border = BorderFactory.createEmptyBorder(10, 16, 6, 16)
        add(picker, BorderLayout.CENTER)
        add(Ui.iconButton(LineIcon.Kind.REFRESH, ctx.strings["action.rescan"]) { ctx.rescan(); refreshInstances(); Ui.toast(this, ctx.strings.f("toast.rescanned", ctx.instances.size)) }, BorderLayout.EAST)
    }

    fun showPage(card: String) {
        cards.show(pages, card)
        navButtons.firstOrNull { it.getClientProperty("navKey") == "nav.$card" }?.isSelected = true
    }

    fun addPending(instanceId: String, n: Int) {
        pending[instanceId] = n
        updateBadge()
    }

    fun clearPending(instanceId: String?) {
        if (instanceId != null && pending.remove(instanceId) != null) updateBadge()
    }

    private fun updateBadge() {
        val n = pending.values.sum()
        navButtons.firstOrNull { it.getClientProperty("navKey") == "nav.diagnose" }?.let { b ->
            b.text = ctx.strings["nav.diagnose"] + if (n > 0) "  ($n)" else ""
        }
    }

    private fun refreshDb(inst: GameInstance?) {
        if (inst == null || !ctx.settings.dbUse) return
        Thread {
            val before = ctx.crashDb.snapshot(inst.profile)?.generated
            val after = runCatching { kotlinx.coroutines.runBlocking { ctx.crashDb.refreshSnapshot(inst.profile) } }.getOrNull()?.generated
            if (after != null && after != before) SwingUtilities.invokeLater { if (selectedInstance?.id == inst.id) diagnosisPage.load(inst) }
        }.apply { isDaemon = true; name = "crashdb-snapshot" }.start()
    }

    fun bringToFront() {
        if (extendedState and java.awt.Frame.ICONIFIED != 0) extendedState = extendedState and java.awt.Frame.ICONIFIED.inv()
        isVisible = true
        toFront()
        requestFocus()
    }

    fun focusCrash(instanceId: String, file: java.io.File?) {
        bringToFront()
        showPage("diagnose")
        diagnosisPage.wantFile = file
        if (!picker.selectId(instanceId)) {
            ctx.rescan()
            if (selectedInstance?.id == instanceId) diagnosisPage.load(selectedInstance) else refreshInstances()
        }
    }

    fun startBisectFor(d: Diagnosis?) {
        bisectPage.useDiagnosis(d)
        showPage("bisect")
    }

    private fun refreshInstances() { if (!fillInstances()) instanceListeners.forEach { it(selectedInstance) } }

    private fun fillInstances(): Boolean {
        val fired = picker.setItems(ctx.instances, ctx.settings.lastInstance)
        if (lastSignature == null) lastSignature = runCatching { ctx.instanceSignatures() }.getOrNull()
        return fired
    }

    private fun rebuildTexts() {
        navButtons.forEach { it.text = ctx.strings[it.getClientProperty("navKey") as String] }
        updateBadge()
        picker.refreshText()
    }

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

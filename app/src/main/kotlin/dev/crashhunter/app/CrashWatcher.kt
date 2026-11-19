package dev.crashhunter.app

import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import javax.swing.SwingUtilities

object TrayNotifier {
    private var icon: TrayIcon? = null
    @Volatile private var onClick: (() -> Unit)? = null

    fun install(onOpen: () -> Unit) {
        if (icon != null || !SystemTray.isSupported()) return
        runCatching {
            val img = AppIcon.images().minByOrNull { kotlin.math.abs(it.getWidth(null) - 16) } ?: return
            val t = TrayIcon(img, "CrashHunter").apply {
                isImageAutoSize = true
                addActionListener { SwingUtilities.invokeLater { (onClick ?: onOpen)(); onClick = null } }
            }
            SystemTray.getSystemTray().add(t)
            icon = t
        }
    }

    fun notify(title: String, text: String, click: () -> Unit) {
        val t = icon ?: return
        onClick = click
        t.displayMessage(title, text, TrayIcon.MessageType.INFO)
    }

    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }
}

class CrashWatcher(private val ctx: AppContext, private val frame: MainFrame) {
    private val seen = HashMap<String, Long>()
    @Volatile private var busy = false
    private var primed = false

    fun tick() {
        if (busy) return
        busy = true
        Thread {
            try { scan() } finally { busy = false }
        }.apply { isDaemon = true; name = "crash-watch" }.start()
    }

    private fun latest(inst: GameInstance): CrashSource? {
        val g = inst.gameDir
        val files = (File(g, "crash-reports").listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray()).map { SourceKind.CRASH_REPORT to it } +
            (g.listFiles { f -> f.isFile && f.name.startsWith("hs_err_pid") } ?: emptyArray()).map { SourceKind.HS_ERR to it }
        val (kind, f) = files.maxByOrNull { it.second.lastModified() } ?: return null
        return CrashSource(kind, f, f.lastModified())
    }

    private fun scan() {
        val list = ctx.instances
        val found = mutableListOf<Pair<GameInstance, CrashSource>>()
        for (inst in list) {
            val src = latest(inst) ?: continue
            val prev = seen.put(inst.id, src.timeMs)
            if (primed && (prev == null || src.timeMs > prev)) found += inst to src
        }
        primed = true
        if (ctx.bisectRunning || ctx.fixRunning) return
        for ((inst, src) in found) report(inst, src)
        if (!ctx.settings.notifyFixes && !ctx.settings.autoApplyFixes) return
        for ((inst, src) in found) handle(inst, src)
    }

    private val sent = HashMap<String, Long>()

    private fun report(inst: GameInstance, src: CrashSource) {
        if (!ctx.settings.dbSend || ctx.settings.dbConfirmSend || src.kind == dev.crashhunter.core.analyze.SourceKind.LATEST_LOG) return
        val index = ModIndex.load(inst.modsDir)
        val d = runCatching { ctx.diagnosis.diagnose(src, inst, index) }.getOrNull() ?: return
        val key = inst.id + "|" + (dev.crashhunter.core.db.DbRefs.fingerprint(d.fingerprint) ?: d.cause.name)
        val now = System.currentTimeMillis()
        if ((sent[key] ?: 0L) > now - 24 * 3600_000L) return
        sent[key] = now
        DbRecorder(ctx).submit(DbRecorder(ctx).fromIngame(inst, d, index), null, interactive = false)
    }

    private fun handle(inst: GameInstance, src: CrashSource) {
        val s = ctx.strings
        val index = ModIndex.load(inst.modsDir)
        val d = runCatching { ctx.diagnosis.diagnose(src, inst, index) }.getOrNull() ?: return
        val proposals = kotlinx.coroutines.runBlocking { runCatching { ctx.fixPlanner().plan(inst, index, listOf(d)) }.getOrDefault(emptyList()) }
        val actionable = proposals.filter { it.actions.isNotEmpty() && it.params["auto"] != "0" }
        val open = { SwingUtilities.invokeLater { frame.focusCrash(inst.id, src.file) } }
        if (ctx.settings.autoApplyFixes && actionable.isNotEmpty() && !ctx.fixRunning && !ctx.bisectRunning) {
            ctx.fixRunning = true
            val out = try {
                kotlinx.coroutines.runBlocking { runCatching { ctx.runFix(inst, actionable, d.fingerprint) {} }.getOrNull() }
            } finally { ctx.fixRunning = false }
            out?.let { o -> DbRecorder(ctx).submit(DbRecorder(ctx).fromFix(inst, o, d), null, interactive = false) }
            if (ctx.settings.notifyFixes) {
                val text = out?.let { FixCard.resultText(ctx, it) } ?: s.t("fix.result.ERROR", mapOf("0" to ""))
                TrayNotifier.notify("CrashHunter", s.t("fix.notify.applied", mapOf("instance" to inst.name, "result" to text)), open)
            }
            return
        }
        if (proposals.isEmpty() || !ctx.settings.notifyFixes) return
        SwingUtilities.invokeLater { frame.addPending(inst.id, proposals.size) }
        TrayNotifier.notify("CrashHunter", s.t("fix.notify.found", mapOf("instance" to inst.name, "n" to proposals.size.toString())), open)
    }
}

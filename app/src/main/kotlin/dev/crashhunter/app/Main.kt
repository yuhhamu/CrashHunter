package dev.crashhunter.app

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.analyze.SolutionKb
import dev.crashhunter.core.bisect.BisectSession
import dev.crashhunter.core.fix.FixApplier
import dev.crashhunter.core.util.AppDirs
import dev.crashhunter.core.util.AppSettings
import java.awt.Image
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val diagIdx = args.indexOf("--diagnose")
    if (diagIdx >= 0) {
        val file = File(args.getOrNull(diagIdx + 1) ?: run { System.err.println("usage: --diagnose <file>"); exitProcess(2) })
        val lang = args.getOrNull(args.indexOf("--lang") + 1).takeIf { "--lang" in args } ?: AppSettings.load().lang
        val d = DiagnosisService(SolutionKb.load(lang)).diagnoseText(file.readText(), file)
        println("cause: ${d.cause}")
        println("title: ${d.title}")
        println("culprit: ${d.topSuspect?.name ?: "-"}")
        println("detail: ${d.detail}")
        d.solutions.forEachIndexed { i, s -> println("${i + 1}. $s") }
        return
    }

    System.setProperty("flatlaf.uiScale.allowScaleDown", "true")
    if (isSystemDark()) FlatDarkLaf.setup() else FlatLightLaf.setup()
    WheelSpeed.install()
    UIManager.put("Component.arc", 8)
    UIManager.put("Button.arc", 8)
    UIManager.put("ScrollBar.width", 10)

    val killed = runCatching { dev.crashhunter.core.launcher.GameTracker(AppDirs.gamesFile).killLeftovers() }.getOrDefault(0)
    if (killed > 0) Thread.sleep(2_000)
    val restored = runCatching { BisectSession.restorePending() }.getOrDefault(0)
    val fixRestored = runCatching { FixApplier(null, AppDirs.fixesDir).recoverInterrupted().size }.getOrDefault(0)
    SwingUtilities.invokeLater {
        EscapeCloser.install()
        val ctx = AppContext()
        val frame = MainFrame(ctx)
        frame.isVisible = true
        SwingUtilities.invokeLater { Onboarding.run(frame, ctx) }
        if (restored > 0) Ui.toast(frame.contentPane, ctx.strings.f("toast.restored", restored))
        if (fixRestored > 0) Ui.toast(frame.contentPane, ctx.strings.f("toast.fixRestored", fixRestored))
        System.getProperty("crashhunter.page")?.let { frame.showPage(it) }
        if (System.getProperty("crashhunter.autostart") == "bisect") javax.swing.Timer(2500) { frame.bisectPage.autoStart() }.apply { isRepeats = false; start() }
    }
}

private fun isSystemDark(): Boolean = runCatching {
    if (!System.getProperty("os.name").lowercase().contains("win")) return false
    val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme").redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x0\b""").containsMatchIn(out)
}.getOrDefault(false)

object AppIcon {
    fun images(): List<Image> = listOf(16, 32, 48, 64, 128, 256).mapNotNull { s ->
        AppIcon::class.java.getResourceAsStream("/crashhunter/icon-$s.png")?.use { ImageIO.read(it) }
    }
}

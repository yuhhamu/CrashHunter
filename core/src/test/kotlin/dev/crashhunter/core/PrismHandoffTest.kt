package dev.crashhunter.core

import dev.crashhunter.core.launcher.LauncherInstallation
import dev.crashhunter.core.launcher.ProcessFinder
import dev.crashhunter.core.prism.PrismController
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrismHandoffTest {
    private val unix = !System.getProperty("os.name").lowercase().contains("win")

    private fun finderOf(f: ((String) -> Boolean) -> List<Long>) = object : ProcessFinder {
        override fun find(predicate: (String) -> Boolean): List<Long> = f(predicate)
    }

    private fun fakePrism(dir: File, output: String): File = File(dir, "prismlauncher").apply {
        writeText("#!/bin/sh\necho '$output'\nexit 0\n")
        setExecutable(true)
    }

    private fun controller(exe: File, logs: File, finder: ProcessFinder = finderOf { emptyList() }) =
        PrismController(LauncherInstallation(exe, exe.parentFile, File(exe.parentFile, "instances")), finder, logs)

    @Test
    fun detectsAFailedHandoff() {
        if (!unix) return
        val dir = Files.createTempDirectory("ch-handoff").toFile()
        val c = controller(fakePrism(dir, "Unable to redirect command to already running instance"), File(dir, "logs"))
        val p = c.launch("inst")
        p.waitFor()
        assertTrue(c.handoffFailed("inst", p))
        assertFalse(c.handoffFailed("other", p))
    }

    @Test
    fun aNormalHandoffIsNotAFailure() {
        if (!unix) return
        val dir = Files.createTempDirectory("ch-handoff").toFile()
        val c = controller(fakePrism(dir, "Instance launched"), File(dir, "logs"))
        val p = c.launch("inst")
        p.waitFor()
        assertFalse(c.handoffFailed("inst", p))
        assertFalse(c.handoffFailed("inst", null))
    }

    @Test
    fun clearsOnlyLaunchersWithoutAGame() {
        if (!unix) return
        val idle = ProcessBuilder("sleep", "30").start()
        val withGame = ProcessBuilder("sh", "-c", "sleep 30 & wait").start()
        Thread.sleep(300)
        val lines = mapOf(
            idle.pid() to "D:/PrismLauncher/prismlauncher.exe -l inst",
            withGame.pid() to "D:/PrismLauncher/prismlauncher.exe -l other",
            ProcessHandle.current().pid() to "javaw.exe -cp D:/PrismLauncher/jars/NewLaunch.jar -l x",
        )
        val finder = finderOf { pred -> lines.filter { pred(it.value) }.keys.toList() }
        val dir = Files.createTempDirectory("ch-handoff").toFile()
        try {
            assertEquals(1, controller(File(dir, "prismlauncher"), File(dir, "logs"), finder).clearStaleLaunchers("inst"))
            idle.waitFor()
            assertFalse(idle.isAlive)
            assertTrue(withGame.isAlive)
        } finally {
            idle.destroyForcibly(); withGame.descendants().forEach { it.destroyForcibly() }; withGame.destroyForcibly()
        }
    }
}

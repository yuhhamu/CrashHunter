package dev.crashhunter.core

import dev.crashhunter.core.launcher.GameTracker
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameTrackerTest {
    private fun longRunning(): Process =
        if (System.getProperty("os.name").lowercase().contains("win")) ProcessBuilder("ping", "-n", "60", "127.0.0.1").start() else ProcessBuilder("sleep", "60").start()

    @Test
    fun `leftover games from a previous run are stopped`() {
        val file = File(Files.createTempDirectory("chtrack").toFile(), "running-games.txt")
        val p = longRunning()
        GameTracker(file).add("inst", listOf(p.pid()))
        assertTrue(file.isFile)
        assertEquals(1, GameTracker(file).killLeftovers())
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        assertFalse(file.exists())
    }

    @Test
    fun `cleared instances are not stopped`() {
        val file = File(Files.createTempDirectory("chtrack").toFile(), "running-games.txt")
        val p = longRunning()
        try {
            GameTracker(file).add("inst", listOf(p.pid()))
            GameTracker(file).clear("inst")
            assertEquals(0, GameTracker(file).killLeftovers())
            assertTrue(p.isAlive)
        } finally { p.destroyForcibly() }
    }
}

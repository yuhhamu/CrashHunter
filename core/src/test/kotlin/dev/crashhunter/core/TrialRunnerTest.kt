package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.JudgeMode
import dev.crashhunter.core.bisect.ManualGate
import dev.crashhunter.core.bisect.MenuMarkers
import dev.crashhunter.core.bisect.ModToggler
import dev.crashhunter.core.bisect.PrismTrialRunner
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.bisect.TrialSettings
import dev.crashhunter.core.launcher.GameLauncher
import dev.crashhunter.core.prism.InstanceScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrialRunnerTest {
    private class FakeGame(val gameDir: File, val behavior: (File) -> Unit) : GameLauncher {
        @Volatile var alive = false
        var stops = 0
        override fun launch(instanceId: String): Process? {
            alive = true
            thread { Thread.sleep(150); behavior(gameDir) }
            return null
        }
        override fun gamePids(instanceId: String) = if (alive) listOf(42L) else emptyList()
        override fun stop(instanceId: String, spawned: Process?) { alive = false; stops++ }
    }

    private fun setup(): Pair<dev.crashhunter.core.model.GameInstance, ModToggler> {
        val root = Files.createTempDirectory("tr").toFile()
        val dir = TestJars.instance(root, "p")
        TestJars.forgeMod(File(dir, "minecraft/mods"), "a.jar", "alpha")
        TestJars.forgeMod(File(dir, "minecraft/mods"), "b.jar", "beta")
        val inst = InstanceScanner.read(dir)
        return inst to ModToggler(inst.modsDir, File(root, "journal.json"))
    }

    private val markers = MenuMarkers(listOf(Regex("TITLE")), listOf(Regex("WEAK")), 1, 2)

    private fun runner(inst: dev.crashhunter.core.model.GameInstance, t: ModToggler, game: FakeGame, mode: JudgeMode = JudgeMode.AUTO, gate: ManualGate = ManualGate(), timeout: Int = 20) =
        PrismTrialRunner(game, inst, t, listOf("a.jar", "b.jar"), TrialSettings(mode, startTimeoutSec = 5, timeoutSec = timeout),
            fingerprintOf = { CrashFingerprint("x") }, markers = markers, gate = gate, pollMs = 50, pidCheckMs = 100, afterStopMs = 10)

    @Test
    fun `title screen then quiet period is a pass, mods are applied`() = runBlocking {
        val (inst, t) = setup()
        val game = FakeGame(inst.gameDir) { g -> File(g, "logs").mkdirs(); File(g, "logs/latest.log").writeText("loading\nTITLE\n") }
        val o = runner(inst, t, game).run(setOf("b.jar"))
        assertEquals(TrialResult.PASS, o.result)
        assertTrue(File(inst.modsDir, "a.jar.disabled").isFile)
        assertEquals(1, game.stops)
    }

    @Test
    fun `weak marker passes once the log goes quiet instead of waiting the full time`() = runBlocking {
        val (inst, t) = setup()
        val game = FakeGame(inst.gameDir) { g -> File(g, "logs").mkdirs(); File(g, "logs/latest.log").writeText("loading\nWEAK\n") }
        val quiet = MenuMarkers(listOf(Regex("TITLE")), listOf(Regex("WEAK")), 1, 30, quietSec = 1)
        val r = PrismTrialRunner(game, inst, t, listOf("a.jar", "b.jar"), TrialSettings(JudgeMode.AUTO, startTimeoutSec = 5, timeoutSec = 60),
            fingerprintOf = { CrashFingerprint("x") }, markers = quiet, pollMs = 50, pidCheckMs = 100, afterStopMs = 10)
        val t0 = System.currentTimeMillis()
        val o = r.run(setOf("a.jar"))
        assertEquals(TrialResult.PASS, o.result)
        assertTrue(System.currentTimeMillis() - t0 < 10_000)
    }

    @Test
    fun `a new crash report is a failure`() = runBlocking {
        val (inst, t) = setup()
        val game = FakeGame(inst.gameDir) { g -> File(g, "crash-reports").mkdirs(); File(g, "crash-reports/crash-1-client.txt").writeText("---- Minecraft Crash Report ----\nDescription: x") }
        val o = runner(inst, t, game).run(setOf("a.jar", "b.jar"))
        assertEquals(TrialResult.FAIL, o.result)
    }

    @Test
    fun `game that closes without a report is a failure`() = runBlocking {
        val (inst, t) = setup()
        lateinit var game: FakeGame
        game = FakeGame(inst.gameDir) { Thread.sleep(300); game.alive = false }
        val o = runner(inst, t, game).run(setOf("a.jar"))
        assertEquals(TrialResult.FAIL, o.result)
    }

    @Test
    fun `game that never shows up fails to start`() = runBlocking {
        val (inst, t) = setup()
        val game = object : GameLauncher {
            override fun launch(instanceId: String): Process? = null
            override fun gamePids(instanceId: String) = emptyList<Long>()
            override fun stop(instanceId: String, spawned: Process?) {}
        }
        val r = PrismTrialRunner(game, inst, t, listOf("a.jar"), TrialSettings(startTimeoutSec = 1), { null }, markers, pollMs = 50, pidCheckMs = 100, afterStopMs = 10, handoffMs = 300)
        assertEquals(TrialResult.START_FAILED, r.run(setOf("a.jar")).result)
    }

    @Test
    fun `no title screen within the time limit is a hang`() = runBlocking {
        val (inst, t) = setup()
        val game = FakeGame(inst.gameDir) { }
        assertEquals(TrialResult.HANG, runner(inst, t, game, timeout = 1).run(setOf("a.jar")).result)
    }

    @Test
    fun `manual verdict`() = runBlocking {
        val (inst, t) = setup()
        val gate = ManualGate()
        val game = FakeGame(inst.gameDir) { Thread.sleep(400); gate.answer = true }
        assertEquals(TrialResult.FAIL, runner(inst, t, game, JudgeMode.MANUAL, gate).run(setOf("a.jar")).result)
    }

    @Test
    fun `parallel trials hand their launches to Prism one at a time`() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun game(name: String, dir: File) = object : GameLauncher {
            @Volatile var startedAt = 0L
            override fun launch(instanceId: String): Process? {
                events += "launch:$name"
                startedAt = System.currentTimeMillis()
                File(dir, "logs").mkdirs(); File(dir, "logs/latest.log").writeText("TITLE\n")
                return null
            }
            override fun gamePids(instanceId: String): List<Long> {
                if (startedAt == 0L || System.currentTimeMillis() - startedAt < 600) return emptyList()
                if (!events.contains("game:$name")) events += "game:$name"
                return listOf(1L)
            }
            override fun stop(instanceId: String, spawned: Process?) { startedAt = 0L }
        }
        val (a, ta) = setup()
        val (b, tb) = setup()
        val ra = PrismTrialRunner(game("a", a.gameDir), a, ta, listOf("a.jar"), TrialSettings(JudgeMode.AUTO, startTimeoutSec = 10, timeoutSec = 20), { null }, markers, pollMs = 50, pidCheckMs = 100, afterStopMs = 10)
        val rb = PrismTrialRunner(game("b", b.gameDir), b, tb, listOf("a.jar"), TrialSettings(JudgeMode.AUTO, startTimeoutSec = 10, timeoutSec = 20), { null }, markers, pollMs = 50, pidCheckMs = 100, afterStopMs = 10)
        val results = kotlinx.coroutines.coroutineScope {
            val x = async(kotlinx.coroutines.Dispatchers.IO) { ra.run(setOf("a.jar")) }
            val y = async(kotlinx.coroutines.Dispatchers.IO) { rb.run(setOf("a.jar")) }
            listOf(x.await(), y.await())
        }
        assertTrue(results.all { it.result == TrialResult.PASS }, results.toString())
        val first = events.first().substringAfter(':')
        val second = if (first == "a") "b" else "a"
        assertTrue(events.indexOf("game:$first") < events.indexOf("launch:$second"), events.toString())
    }
}

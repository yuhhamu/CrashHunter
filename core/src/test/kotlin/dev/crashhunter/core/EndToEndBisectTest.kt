package dev.crashhunter.core

import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.analyze.SolutionKb
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.bisect.BisectSession
import dev.crashhunter.core.bisect.BisectStatus
import dev.crashhunter.core.bisect.WorkCopy
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.prism.InstanceScanner
import dev.crashhunter.core.launcher.LauncherInstallation
import dev.crashhunter.core.util.AppSettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndToEndBisectTest {
    private val crashReport = """
        ---- Minecraft Crash Report ----
        Description: Ticking entity

        java.lang.IllegalStateException: badmod exploded
        	at com.example.badmod.Boom.tick(Boom.java:10) ~[bad.jar%23100!/:1.0] {re:classloading}
        	at net.minecraft.world.entity.Entity.tick(Entity.java:1) ~[forge-1.20.1.jar%2391!/:?] {}

        -- Head --
        Suspected Mods:
        	Bad Mod (badmod), Version: 1.0
    """.trimIndent()

    @Test
    fun `bisect finds bad jar through a fake prism`() = runBlocking {
        if (System.getProperty("os.name").lowercase().contains("win")) return@runBlocking
        val data = Files.createTempDirectory("prism").toFile()
        val instances = File(data, "instances").also { it.mkdirs() }
        val dir = TestJars.instance(instances, "Pack One")
        val mods = File(dir, "minecraft/mods")
        TestJars.forgeMod(mods, "lib.jar", "lib")
        TestJars.forgeMod(mods, "needslib.jar", "needslib", deps = TestJars.dep("needslib", "lib", "[1,)"))
        TestJars.forgeMod(mods, "bad.jar", "badmod", pkg = "com.example.badmod", deps = TestJars.dep("badmod", "lib", "[1,)"))
        for (i in 1..12) TestJars.forgeMod(mods, "m$i.jar", "m$i")
        File(dir, "minecraft/crash-reports").mkdirs()
        File(dir, "minecraft/crash-reports/crash-old-client.txt").writeText(crashReport)

        val fixture = File(data, "crash.txt").also { it.writeText(crashReport) }
        val exe = File(data, "prismlauncher")
        exe.writeText(
            """
            #!/bin/bash
            id="${'$'}2"; g="${instances.absolutePath}/${'$'}id/minecraft"
            mkdir -p "${'$'}g/logs"
            (
              if [ -f "${'$'}g/mods/bad.jar" ] && [ -f "${'$'}g/mods/lib.jar" ]; then
                sleep 1; mkdir -p "${'$'}g/crash-reports"; cp "${fixture.absolutePath}" "${'$'}g/crash-reports/crash-${'$'}(date +%s%N)-client.txt"
              else
                echo "[Realms Notification Availability checker #1/INFO]" > "${'$'}g/logs/latest.log"
                bash -c 'sleep 120; true' "java -Djava.library.path=${instances.absolutePath}/${'$'}id/natives/"
              fi
            ) &
            bash -c 'sleep 2; true' "java -Djava.library.path=${instances.absolutePath}/${'$'}id/natives/ boot" &
            """.trimIndent(),
        )
        exe.setExecutable(true)

        val install = LauncherInstallation(exe, data, instances)
        val inst = InstanceScanner.read(dir)
        val service = DiagnosisService(SolutionKb.load("en"))
        val idx = ModIndex.load(inst.modsDir)
        val d = service.diagnose(service.sources(inst).first { it.kind == SourceKind.CRASH_REPORT }, inst, idx)
        assertEquals("bad.jar", d.topSuspect?.jar)

        val journal = File(data, "journal.json")
        val settings = AppSettings(startTimeoutSec = 20, timeoutSec = 60, settleSec = 1)
        val report = BisectSession(dev.crashhunter.core.prism.PrismLauncher(install), inst, settings, service, d, journalFile = journal).run()
        assertEquals(BisectStatus.FOUND, report.result.status, report.result.toString())
        assertEquals(listOf("bad.jar"), report.culprits.map { it.fileName })
        assertEquals(listOf("lib.jar"), report.requirements.map { it.fileName })
        assertTrue(report.result.trials <= 6, "trials ${report.result.trials}")
        val copy = WorkCopy.dirFor(inst)
        assertTrue(File(copy, "minecraft/mods").listFiles()!!.none { it.name.endsWith(".disabled") })
        assertTrue(mods.listFiles()!!.none { it.name.endsWith(".disabled") })
        assertTrue(!journal.exists())
        Runtime.getRuntime().exec(arrayOf("pkill", "-f", instances.absolutePath))
        Unit
    }
}

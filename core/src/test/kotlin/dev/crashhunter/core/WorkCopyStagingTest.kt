package dev.crashhunter.core

import dev.crashhunter.core.bisect.WorkCopy
import dev.crashhunter.core.mods.CrashTools
import dev.crashhunter.core.prism.InstanceScanner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkCopyStagingTest {
    private fun instance(root: File, name: String): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "instance.cfg").writeText("InstanceType=OneSix\nname=$name\n")
        File(dir, "mmc-pack.json").writeText("""{"components":[{"uid":"net.minecraft","version":"1.20.1"}],"formatVersion":1}""")
        File(dir, "minecraft/mods").mkdirs()
        File(dir, "minecraft/config").mkdirs()
        File(dir, "minecraft/config/a.toml").writeText("x=1")
        return dir
    }

    @Test
    fun copyAppearsOnlyWhenComplete() {
        val root = Files.createTempDirectory("ch-stage").toFile()
        instance(root, "pack")
        val source = InstanceScanner.scan(root).single()
        val copy = WorkCopy.create(source, false, slot = 1)
        assertTrue(File(copy.dir, "instance.cfg").isFile)
        assertTrue(File(copy.dir, "minecraft/config/a.toml").isFile)
        assertFalse(root.listFiles()!!.any { it.name.endsWith(WorkCopy.STAGING_SUFFIX) })
        WorkCopy.delete(copy.dir)
        assertFalse(copy.dir.exists())
    }

    @Test
    fun anEmptyLeftoverFolderIsReplaced() {
        val root = Files.createTempDirectory("ch-stage").toFile()
        instance(root, "pack")
        val source = InstanceScanner.scan(root).single()
        WorkCopy.dirFor(source, 1).mkdirs()
        val copy = WorkCopy.create(source, false, slot = 1)
        assertTrue(WorkCopy.isWorkCopy(copy.dir))
    }

    @Test
    fun disablesCrashTools() {
        val root = Files.createTempDirectory("ch-tools").toFile()
        val mods = File(root, "mods").apply { mkdirs() }
        val jar = File(mods, "CrashAssistant-forge-1.20.1.jar")
        java.util.zip.ZipOutputStream(jar.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("META-INF/mods.toml"))
            z.write("modLoader=\"javafml\"\nloaderVersion=\"[41,)\"\n[[mods]]\nmodId=\"crash_assistant\"\nversion=\"1.0\"\ndisplayName=\"Crash Assistant\"\n".toByteArray())
            z.closeEntry()
        }
        assertEquals(listOf("Crash Assistant"), CrashTools.disable(mods))
        assertTrue(File(mods, jar.name + ".disabled").isFile)
        assertEquals(emptyList(), CrashTools.disable(mods))
    }
}

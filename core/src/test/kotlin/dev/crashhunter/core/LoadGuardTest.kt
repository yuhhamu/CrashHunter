package dev.crashhunter.core

import dev.crashhunter.core.bisect.LoadGuard
import dev.crashhunter.core.bisect.WorkCopy
import dev.crashhunter.core.prism.InstanceScanner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadGuardTest {
    @Test
    fun `only work copies change and a shared file is not rewritten in place`() {
        val root = Files.createTempDirectory("lg").toFile()
        val orig = TestJars.instance(root, "orig")
        val copy = TestJars.instance(root, "copy")
        File(copy, WorkCopy.MARKER).writeText("x")
        val origToml = File(orig, "minecraft/config/fml.toml").apply { parentFile.mkdirs(); writeText("earlyWindowControl = true\nmaxThreads = -1\n") }
        val copyToml = File(copy, "minecraft/config/fml.toml").apply { parentFile.mkdirs() }
        Files.createLink(copyToml.toPath(), origToml.toPath())
        val o = InstanceScanner.read(orig)
        val c = InstanceScanner.read(copy)
        assertFalse(LoadGuard.limitThreads(o))
        assertTrue(LoadGuard.limitThreads(c))
        assertEquals("earlyWindowControl = true\nmaxThreads = -1\n", origToml.readText())
        assertEquals("earlyWindowControl = true\nmaxThreads = 1\n", copyToml.readText())
        assertTrue(LoadGuard.singleThreaded(c))
        assertFalse(LoadGuard.limitThreads(c))
    }
}

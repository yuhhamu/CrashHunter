package dev.crashhunter.core

import dev.crashhunter.core.analyze.StackTraceTools
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactPathsTest {
    private fun clean(s: String) = StackTraceTools.stripPaths(s)

    @Test
    fun `paths with spaces and network paths are hidden`() {
        val a = clean("Failed to load C:\\Users\\John Smith\\AppData\\Roaming\\PrismLauncher\\instances\\My Pack\\minecraft\\mods\\a.jar now")
        assertFalse(a.contains("John") || a.contains("Smith") || a.contains("My Pack"), a)
        assertTrue(a.startsWith("Failed to load <path>"), a)
        assertTrue(a.endsWith("now"), a)
        val b = clean("at \\\\nas-01\\Share Folder\\games\\mods\\b.jar")
        assertFalse(b.contains("nas-01") || b.contains("Share"), b)
        val c = clean("Opening /home/alice/.local/share/PrismLauncher/x.jar")
        assertFalse(c.contains("alice"), c)
    }

    @Test
    fun `tokens and names in launch arguments are hidden`() {
        val t = clean("--username Steve --accessToken eyJhbGciOi --uuid 123e4567-e89b-12d3-a456-426614174000")
        assertFalse(t.contains("Steve") || t.contains("eyJhbGciOi") || t.contains("123e4567"), t)
    }

    @Test
    fun `class names and messages stay readable`() {
        val m = clean("java.lang.NoSuchMethodError: 'void net.minecraft.world.level.Level.foo(int)'")
        assertTrue(m.contains("net.minecraft.world.level.Level.foo"), m)
    }
}

class RedactorTextTest {
    @Test
    fun `report text hides names with spaces and shares`() {
        val r = dev.crashhunter.core.util.Redactor.redact("C:\\Users\\John Smith\\AppData\\x.log and \\\\nas-01\\Share Folder\\mods and https://example.com/a")
        assertFalse(r.contains("John") || r.contains("Smith") || r.contains("nas-01") || r.contains("Share Folder"), r)
        assertTrue(r.contains("https://example.com/a"), r)
    }
}

class RedactUrlTest {
    @Test
    fun `urls are not treated as drive or network paths`() {
        val u = StackTraceTools.stripPaths("see https://example.com/page now")
        assertTrue(u.contains("https:") && u.contains("example.com"), u)
    }
}

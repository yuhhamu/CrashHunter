package dev.crashhunter.core

import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.scan.StaticKind
import dev.crashhunter.core.scan.StaticScan
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticScanTest {
    private fun compile(sources: Map<String, String>, classpath: File? = null): File {
        val src = Files.createTempDirectory("src").toFile()
        val out = Files.createTempDirectory("cls").toFile()
        val files = sources.map { (name, text) -> File(src, name).also { it.parentFile.mkdirs(); it.writeText(text) } }
        val args = mutableListOf("-d", out.path, "--release", "17")
        if (classpath != null) { args += "-cp"; args += classpath.path }
        args += files.map { it.path }
        val rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray())
        assertEquals(0, rc)
        return out
    }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun classes(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.associate { it.relativeTo(dir).path.replace('\\', '/') to it.readBytes() }

    private fun toml(id: String) = """
        modLoader="javafml"
        loaderVersion="[47,)"
        license="MIT"
        [[mods]]
        modId="$id"
        version="1.0"
    """.trimIndent().toByteArray()

    @Test
    fun `finds a final override and a missing method across nested library versions`() {
        val oldLib = compile(mapOf("lib/Base.java" to "package lib; public class Base { public void run() {} public static void gone() {} }"))
        val newLib = compile(mapOf("lib/Base.java" to "package lib; public class Base { public final void run() {} }"))
        val addon = compile(mapOf("addon/Jelly.java" to "package addon; public class Jelly extends lib.Base { public void run() {} public void use() { lib.Base.gone(); } }"), oldLib)
        val mods = Files.createTempDirectory("mods").toFile()
        val nested = zipBytes(classes(newLib))
        File(mods, "library.jar").writeBytes(zipBytes(mapOf(
            "META-INF/mods.toml" to toml("library"),
            "META-INF/jarjar/metadata.json" to """{"jars":[{"identifier":{"group":"x","artifact":"lib"},"version":{"artifactVersion":"1.2.0"},"path":"META-INF/jarjar/lib-1.2.0.jar"}]}""".toByteArray(),
            "META-INF/jarjar/lib-1.2.0.jar" to nested,
            "library/Main.class" to classes(compile(mapOf("library/Main.java" to "package library; public class Main {}"))).values.first(),
        )))
        File(mods, "olderlib.jar").writeBytes(zipBytes(mapOf(
            "META-INF/mods.toml" to toml("olderlib"),
            "META-INF/jarjar/metadata.json" to """{"jars":[{"identifier":{"group":"x","artifact":"lib"},"version":{"artifactVersion":"1.1.0"},"path":"META-INF/jarjar/lib-1.1.0.jar"}]}""".toByteArray(),
            "META-INF/jarjar/lib-1.1.0.jar" to zipBytes(classes(oldLib)),
        )))
        File(mods, "addon.jar").writeBytes(zipBytes(mapOf("META-INF/mods.toml" to toml("addon")) + classes(addon)))

        val cache = Files.createTempDirectory("cache").toFile()
        val found = StaticScan(cache).scan(ModIndex.load(mods))
        val over = found.single { it.kind == StaticKind.FINAL_OVERRIDE }
        assertEquals("addon.jar", over.file)
        assertEquals("library.jar", over.other)
        assertTrue(over.target.startsWith("lib/Base#run"))
        val missing = found.single { it.kind == StaticKind.MISSING_METHOD }
        assertTrue(missing.target.contains("gone"))
        assertTrue(cache.listFiles()!!.isNotEmpty())
        assertEquals(found, StaticScan(cache).scan(ModIndex.load(mods)))
    }

    @Test
    fun `nothing is reported when the newest nested library matches`() {
        val lib = compile(mapOf("lib/Base.java" to "package lib; public class Base { public void run() {} }"))
        val addon = compile(mapOf("addon/Jelly.java" to "package addon; public class Jelly extends lib.Base { public void run() {} }"), lib)
        val mods = Files.createTempDirectory("mods").toFile()
        File(mods, "library.jar").writeBytes(zipBytes(mapOf("META-INF/mods.toml" to toml("library")) + classes(lib)))
        File(mods, "addon.jar").writeBytes(zipBytes(mapOf("META-INF/mods.toml" to toml("addon")) + classes(addon)))
        assertTrue(StaticScan().scan(ModIndex.load(mods)).isEmpty())
    }
}

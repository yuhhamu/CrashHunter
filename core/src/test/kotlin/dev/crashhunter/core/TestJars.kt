package dev.crashhunter.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestJars {
    fun jar(file: File, entries: Map<String, String>): File {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return file
    }

    fun forgeMod(dir: File, file: String, id: String, version: String = "1.0", deps: String = "", pkg: String = "com.example.$id"): File =
        jar(File(dir, file), mapOf(
            "META-INF/mods.toml" to """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="$id"
                version="$version"
                displayName="${id.replaceFirstChar { it.uppercase() }}"
                [[dependencies.$id]]
                    modId="forge"
                    mandatory=true
                    versionRange="[47,)"
                    ordering="NONE"
                    side="BOTH"
                [[dependencies.$id]]
                    modId="minecraft"
                    mandatory=true
                    versionRange="[1.20.1,1.21)"
                    ordering="NONE"
                    side="BOTH"
                $deps
            """.trimIndent(),
            pkg.replace('.', '/') + "/Main.class" to "x",
        ))

    fun dep(owner: String, id: String, range: String, mandatory: Boolean = true) = """
        [[dependencies.$owner]]
            modId="$id"
            mandatory=$mandatory
            versionRange="$range"
            ordering="NONE"
            side="BOTH"
    """.trimIndent()

    fun fabricMod(dir: File, file: String, id: String, depends: String = "{}"): File =
        jar(File(dir, file), mapOf("fabric.mod.json" to """{"schemaVersion":1,"id":"$id","version":"1.0","depends":$depends}""", "net/$id/A.class" to "x"))

    fun instance(root: File, id: String, mc: String = "1.20.1", loaderUid: String = "net.minecraftforge", loaderVersion: String = "47.4.6"): File {
        val dir = File(root, id)
        File(dir, "minecraft/mods").mkdirs()
        File(dir, "instance.cfg").writeText("[General]\nInstanceType=OneSix\nname=$id name\n")
        File(dir, "mmc-pack.json").writeText("""{"components":[{"uid":"net.minecraft","version":"$mc"},{"uid":"$loaderUid","version":"$loaderVersion"}],"formatVersion":1}""")
        return dir
    }
}

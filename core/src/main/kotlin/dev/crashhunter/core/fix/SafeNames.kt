package dev.crashhunter.core.fix

import java.io.File

object SafeNames {
    private val BAD = Regex("""[\u0000-\u001f<>:"|?*]""")

    fun jar(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
        require(base.isNotEmpty() && !base.startsWith(".")) { "bad file name: $name" }
        require(base.endsWith(".jar", ignoreCase = true)) { "not a jar: $name" }
        require(!BAD.containsMatchIn(base) && base.length <= 200) { "bad file name: $name" }
        return base
    }

    fun inside(dir: File, name: String): File {
        val f = File(dir, jar(name))
        val parent = f.canonicalFile.parentFile
        require(parent == dir.canonicalFile) { "outside of ${dir.name}: $name" }
        return f
    }
}

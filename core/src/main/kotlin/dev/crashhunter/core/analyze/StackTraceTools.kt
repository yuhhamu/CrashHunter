package dev.crashhunter.core.analyze

import kotlinx.serialization.Serializable

@Serializable
data class CrashFingerprint(
    val exception: String,
    val frames: List<String> = emptyList(),
    val message: String = "",
    val mods: List<String> = emptyList(),
    val cause: String? = null,
)

object StackTraceTools {
    private val pathLike = Regex("""([A-Za-z]:[\\/][^\s]*|/[\w.\-/]{3,})""")
    private val noise = Regex("""(0x[0-9a-fA-F]+|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}|\d+)""")
    private val ignoredPrefixes = listOf(
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "net.minecraft.", "com.mojang.",
        "net.minecraftforge.", "net.neoforged.", "cpw.mods.", "net.fabricmc.", "org.quiltmc.", "org.spongepowered.",
        "org.lwjgl.", "io.netty.", "com.google.", "org.apache.", "it.unimi.", "org.slf4j.", "MC-BOOTSTRAP",
        "org.objectweb.", "com.llamalad7.mixinextras.", "org.openjdk.", "jdk.internal.", "org.jline.",
    )

    fun isPlatformClass(cls: String): Boolean = ignoredPrefixes.any { cls.startsWith(it) }

    private val userLike = Regex("""(?i)(users|home)[\\/][^\\/\s]+""")

    fun stripPaths(text: String?): String =
        (text ?: "").replace(pathLike, "<path>").replace(userLike, "<user>").trim()

    fun normalizeMessage(message: String?): String =
        (message ?: "").replace(pathLike, "<path>").replace(noise, "<n>").trim()

    fun tokens(message: String?): List<String> =
        normalizeMessage(message).lowercase().split(Regex("""[^a-z0-9_.$]+""")).filter { it.length >= 5 && it != "<path>" && it != "<n>" }.distinct()

    fun suspectedModIds(text: String): List<String> {
        val ids = LinkedHashSet<String>()
        Regex("""(?m)^\s*Suspected Mods?:[ \t]*(.*)$((?:\r?\n[ \t]+.*)*)""").findAll(text).forEach { m ->
            Regex("""\(([a-z][a-z0-9_\-]{1,63})\)""").findAll(m.groupValues[1] + "\n" + m.groupValues[2]).forEach { ids += it.groupValues[1] }
        }
        Regex("""from mod ([a-z][a-z0-9_\-]{1,63})""").findAll(text).forEach { ids += it.groupValues[1] }
        Regex("""(?m)^-- MOD ([a-z][a-z0-9_\-]{1,63}) --""").findAll(text).forEach { ids += it.groupValues[1] }
        return ids.filter { it !in setOf("minecraft", "forge", "neoforge", "fml", "none") }.take(10)
    }

    fun fingerprint(p: ParsedCrash, cause: String? = null): CrashFingerprint? {
        val exc = p.effectiveException ?: p.nativeFrame?.let { "native:$it" } ?: cause?.let { "cause:$it" } ?: return null
        return CrashFingerprint(
            exception = exc,
            frames = p.frames.map { it.className }.filter { !isPlatformClass(it) }.distinct().take(5),
            message = normalizeMessage(p.effectiveMessage).take(200),
            mods = (p.modFailures.map { it.modId } + p.suspectedMods + p.mixinMods).distinct().take(5),
            cause = cause,
        )
    }

    fun sameFailure(a: CrashFingerprint, b: CrashFingerprint): Boolean {
        val excSame = a.exception == b.exception || simple(a.exception) == simple(b.exception)
        val framesShared = a.frames.any { it in b.frames }
        val modsShared = a.mods.any { it in b.mods }
        val msgShared = a.message.isNotBlank() && a.message == b.message
        if (excSame && (framesShared || modsShared || msgShared)) return true
        if (excSame && a.frames.isEmpty() && b.frames.isEmpty() && a.mods.isEmpty() && b.mods.isEmpty()) return true
        if (a.cause != null && a.cause == b.cause && modsShared) return true
        return false
    }

    private fun simple(name: String) = name.substringAfterLast('.').substringAfterLast('$')
}

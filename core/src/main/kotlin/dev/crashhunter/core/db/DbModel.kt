package dev.crashhunter.core.db

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.mods.ModJar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

const val DB_SCHEMA = 1

@Serializable
data class ModRef(val id: String, val v: String? = null, val sha1: String? = null, val cf: Long? = null)

@Serializable
data class DbEnv(val mc: String, val loader: String, val loaderVersion: String? = null, val java: Int? = null, val os: String? = null, val arch: String? = null)

@Serializable
sealed interface DbItem

@Serializable
@SerialName("conflict")
data class ConflictItem(val mods: List<ModRef>, val fp: String? = null, val ex: String? = null, val method: String, val trials: Int = 0) : DbItem

@Serializable
@SerialName("fix")
data class FixItem(val kind: String, val from: List<ModRef>, val to: List<ModRef>, val result: String, val fp: String? = null) : DbItem

@Serializable
@SerialName("broken")
data class BrokenItem(val mod: ModRef, val reason: String) : DbItem

@Serializable
@SerialName("compatible")
data class CompatibleItem(val mods: List<ModRef>) : DbItem

@Serializable
data class ExRef(val ex: String, val msg: String? = null)

@Serializable
data class FrameRef(val c: String, val m: String, val src: String? = null, val mod: String? = null)

@Serializable
data class FindingRef(
    val cause: String,
    val mod: String? = null,
    val other: String? = null,
    val required: String? = null,
    val actual: String? = null,
    val config: String? = null,
    val line: String? = null,
)

@Serializable
@SerialName("crash")
data class CrashItem(
    val context: String,
    val source: String,
    val cause: String,
    val fp: String? = null,
    val ex: String? = null,
    val msg: String? = null,
    val phase: String? = null,
    val causes: List<ExRef> = emptyList(),
    val frames: List<FrameRef> = emptyList(),
    val mods: List<ModRef> = emptyList(),
    val findings: List<FindingRef> = emptyList(),
    val sys: Map<String, String> = emptyMap(),
    val heapMb: Long? = null,
    val seen: Int? = null,
) : DbItem

@Serializable
@SerialName("dependency")
data class DependencyItem(val mod: ModRef, val needs: ModRef, val declared: Boolean, val range: String? = null) : DbItem

@Serializable
data class DbReport(
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val schema: Int = DB_SCHEMA,
    val install: String,
    val app: String,
    val time: Long,
    val env: DbEnv,
    val items: List<DbItem>,
)

@Serializable
data class SnapConflict(val mods: List<ModRef>, val fp: String? = null, val ex: String? = null, val w: Double = 0.0, val n: Int = 0)

@Serializable
data class SnapFix(val kind: String, val from: List<ModRef>, val to: List<ModRef>, val fp: String? = null, val w: Double = 0.0, val n: Int = 0)

@Serializable
data class SnapBroken(val mod: ModRef, val reason: String, val w: Double = 0.0, val n: Int = 0, val confirmed: Boolean = false)

@Serializable
data class DbSnapshot(
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val schema: Int = DB_SCHEMA,
    val mc: String,
    val loader: String,
    val generated: Long = 0,
    val conflicts: List<SnapConflict> = emptyList(),
    val fixes: List<SnapFix> = emptyList(),
    val broken: List<SnapBroken> = emptyList(),
)

@Serializable
data class CheckBroken(val mod: ModRef, val reason: String, val n: Int = 0, val confirmed: Boolean = false)

@Serializable
data class CheckConflict(val mods: List<ModRef>, val ex: String? = null, val n: Int = 0)

@Serializable
data class CheckFix(val kind: String, val from: List<ModRef>, val to: List<ModRef>, val n: Int = 0)

@Serializable
data class CheckKnown(val broken: List<CheckBroken> = emptyList(), val conflicts: List<CheckConflict> = emptyList(), val fixes: List<CheckFix> = emptyList())

@Serializable
data class CheckCompatible(val reports: Int = 0, val supersets: Int = 0, val installs: Int = 0, val best: Double = 0.0, val unseen: List<ModRef> = emptyList())

@Serializable
data class CheckCrash(val mod: ModRef, val installs: Int = 0, val causes: Map<String, Int> = emptyMap())

@Serializable
data class DbCheck(
    val generated: Long = 0,
    val known: CheckKnown = CheckKnown(),
    val compatible: CheckCompatible = CheckCompatible(),
    val crashes: List<CheckCrash> = emptyList(),
)

object DbRefs {
    private val SAFE_VERSION = Regex("""^[\w.+\-]{1,64}$""")

    fun sha1(file: java.io.File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun of(jar: ModJar, withHash: Boolean = true): ModRef {
        val m = jar.mods.firstOrNull()
        val id = m?.modId ?: jar.fileName.removeSuffix(".jar").replace(Regex("""[-_+][0-9].*$"""), "").lowercase()
        val safeId = id.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "_.-" }.take(64).ifEmpty { "unknown" }
        val v = m?.version?.takeIf { !it.contains("\${") && SAFE_VERSION.matches(it) }
        if (!withHash) return ModRef(safeId, v)
        val cf = runCatching { dev.crashhunter.core.fix.remote.Murmur2.curseForgeFingerprint(jar.file) }.getOrNull()
        return ModRef(safeId, v, sha1(jar.file), cf)
    }

    fun env(profile: GameProfile, java: Int? = null): DbEnv? {
        val mc = profile.mcVersion ?: return null
        return DbEnv(mc, profile.loader.name.lowercase(), profile.loaderVersion, java, osName(), System.getProperty("os.arch")?.take(16))
    }

    fun osName(): String? {
        val n = System.getProperty("os.name") ?: return null
        val v = System.getProperty("os.version")?.substringBefore('.')?.takeIf { it.all(Char::isDigit) }
        return listOfNotNull(n, v).joinToString(" ").take(32)
    }

    fun fingerprint(fp: CrashFingerprint?): String? {
        fp ?: return null
        val text = listOf(fp.exception, fp.cause ?: "", fp.frames.take(6).joinToString(";")).joinToString("|")
        val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun exceptionName(fp: CrashFingerprint?): String? = fp?.exception?.takeUnless { it.startsWith("cause:") || it.startsWith("native:") }
        ?.substringAfterLast('.')?.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '$' }?.take(80)?.ifEmpty { null }

    fun matches(ref: ModRef, jar: ModJar, jarSha1: () -> String?): Int {
        if (ref.sha1 != null && ref.sha1 == jarSha1()) return 2
        val m = jar.mods.firstOrNull { it.modId.equals(ref.id, true) } ?: return 0
        return if (ref.v != null && ref.v == m.version) 1 else 0
    }
}

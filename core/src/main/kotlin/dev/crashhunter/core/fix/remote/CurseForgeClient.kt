package dev.crashhunter.core.fix.remote

import dev.crashhunter.core.fix.RemoteFile
import dev.crashhunter.core.fix.RemoteSource
import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.model.Loader
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CfHash(val value: String, val algo: Int)

@Serializable
data class CfFile(
    val id: Long,
    val modId: Long,
    val displayName: String = "",
    val fileName: String,
    val downloadUrl: String? = null,
    val hashes: List<CfHash> = emptyList(),
    val gameVersions: List<String> = emptyList(),
    val fileDate: String = "",
    val releaseType: Int = 1,
    val isAvailable: Boolean = true,
)

@Serializable
data class CfLinks(val websiteUrl: String? = null)

@Serializable
data class CfMod(val id: Long, val name: String = "", val slug: String = "", val allowModDistribution: Boolean? = null, val links: CfLinks? = null, val authors: List<CfAuthor> = emptyList())

@Serializable
data class CfAuthor(val name: String = "")

@Serializable
private data class CfData<T>(val data: T)

@Serializable
private data class CfMatch(val id: Long, val file: CfFile)

@Serializable
private data class CfMatches(val exactMatches: List<CfMatch> = emptyList())

class NoCurseForgeKeyException : RuntimeException("no CurseForge API key")

enum class KeyCheck { OK, REJECTED, WRONG_FORMAT, NETWORK, ERROR, EMPTY }

class CurseForgeKeys(
    private val appKey: () -> String?,
    private val userKey: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val pauseMs: Long = 10 * 60_000,
) {
    @Volatile private var appPausedUntil = 0L
    @Volatile var lastUsed: String = "none"
        private set

    fun available(): Boolean = !appKey().isNullOrBlank() || !userKey().isNullOrBlank()

    fun order(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        appKey()?.takeIf { it.isNotBlank() && now() >= appPausedUntil }?.let { list += "app" to it }
        userKey()?.takeIf { it.isNotBlank() }?.let { list += "user" to it }
        return list
    }

    fun limited(which: String) {
        if (which == "app") appPausedUntil = now() + pauseMs
    }

    fun used(which: String) { lastUsed = which }
}

class CurseForgeClient(private val http: Http, private val keys: CurseForgeKeys, private val base: String = "https://api.curseforge.com/v1") {
    private suspend fun <T> withKey(block: suspend (String) -> T): T {
        val order = keys.order()
        if (order.isEmpty()) throw NoCurseForgeKeyException()
        var last: Exception? = null
        for ((which, key) in order) {
            try {
                return block(key).also { keys.used(which) }
            } catch (e: HttpStatusException) {
                if (e.status == 429 || e.status == 403) { keys.limited(which); last = e; continue }
                throw e
            }
        }
        throw last ?: NoCurseForgeKeyException()
    }

    suspend fun fingerprintMatch(file: File): Pair<CfMod?, CfFile>? = withKey { key ->
        val fp = Murmur2.curseForgeFingerprint(file)
        val text = http.post("$base/fingerprints/432", "{\"fingerprints\":[$fp]}", mapOf("x-api-key" to key))
        val m = http.json.decodeFromString(CfData.serializer(CfMatches.serializer()), text).data.exactMatches.firstOrNull() ?: return@withKey null
        null to m.file
    }

    suspend fun mod(id: Long): CfMod? = withKey { key ->
        notFoundAsNull { http.json.decodeFromString(CfData.serializer(CfMod.serializer()), http.get("$base/mods/$id", mapOf("x-api-key" to key))).data }
    }

    suspend fun bySlug(slug: String): CfMod? = withKey { key ->
        val text = http.get("$base/mods/search?gameId=432&classId=6&slug=${Http.enc(slug)}", mapOf("x-api-key" to key))
        http.json.decodeFromString(CfData.serializer(kotlinx.serialization.builtins.ListSerializer(CfMod.serializer())), text).data.firstOrNull()
    }

    suspend fun files(modId: Long, profile: GameProfile): List<CfFile> = withKey { key ->
        val mc = profile.mcVersion ?: return@withKey emptyList()
        val lt = loaderType(profile.loader)?.let { "&modLoaderType=$it" } ?: ""
        val text = http.get("$base/mods/$modId/files?gameVersion=${Http.enc(mc)}$lt&pageSize=20", mapOf("x-api-key" to key))
        http.json.decodeFromString(CfData.serializer(kotlinx.serialization.builtins.ListSerializer(CfFile.serializer())), text).data
            .filter { it.isAvailable }.sortedWith(compareBy<CfFile> { it.releaseType }.thenByDescending { it.fileDate })
    }

    suspend fun allFiles(modId: Long, profile: GameProfile): List<CfFile> = withKey { key ->
        val mc = profile.mcVersion ?: return@withKey emptyList()
        val lt = loaderType(profile.loader)?.let { "&modLoaderType=$it" } ?: ""
        val text = http.get("$base/mods/$modId/files?gameVersion=${Http.enc(mc)}$lt&pageSize=50", mapOf("x-api-key" to key))
        http.json.decodeFromString(CfData.serializer(kotlinx.serialization.builtins.ListSerializer(CfFile.serializer())), text).data
            .filter { it.isAvailable }.sortedByDescending { it.fileDate }
    }

    suspend fun checkKey(key: String): KeyCheck {
        val k = key.trim()
        if (k.isEmpty()) return KeyCheck.EMPTY
        return try {
            http.get("$base/games/432", mapOf("x-api-key" to k))
            KeyCheck.OK
        } catch (e: HttpStatusException) {
            if (e.status == 401 || e.status == 403) (if (looksLikeKey(k)) KeyCheck.REJECTED else KeyCheck.WRONG_FORMAT) else KeyCheck.ERROR
        } catch (e: Exception) {
            KeyCheck.NETWORK
        }
    }

    suspend fun download(url: String, dest: File): File = withKey { key -> http.download(url, dest, mapOf("x-api-key" to key)) }

    companion object {
        fun looksLikeKey(k: String): Boolean = k.startsWith("$2a$") && k.length in 50..80

        fun loaderType(loader: Loader): Int? = when (loader) {
            Loader.FORGE, Loader.FORGE_LEGACY -> 1
            Loader.FABRIC -> 4
            Loader.QUILT -> 5
            Loader.NEOFORGE -> 6
            else -> null
        }

        fun pageUrl(mod: CfMod?, modId: Long): String = mod?.links?.websiteUrl ?: "https://www.curseforge.com/projects/$modId"

        fun toRemote(f: CfFile, mod: CfMod?): RemoteFile = RemoteFile(RemoteSource.CURSEFORGE, f.fileName, f.downloadUrl,
            f.hashes.firstOrNull { it.algo == 1 }?.value, null, f.modId.toString(), f.id.toString(), f.displayName.ifBlank { f.fileName }, pageUrl(mod, f.modId),
            mod?.name?.ifBlank { null }, mod?.authors?.firstOrNull()?.name?.ifBlank { null })
    }
}

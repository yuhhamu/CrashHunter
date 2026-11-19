package dev.crashhunter.core.fix.remote

import dev.crashhunter.core.fix.RemoteFile
import dev.crashhunter.core.fix.RemoteSource
import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.model.Loader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class MrFile(val url: String, val filename: String, val primary: Boolean = false, val hashes: Map<String, String> = emptyMap(), val size: Long = 0)

@Serializable
data class MrVersion(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("version_number") val versionNumber: String = "",
    @SerialName("version_type") val versionType: String = "release",
    val loaders: List<String> = emptyList(),
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val files: List<MrFile> = emptyList(),
    @SerialName("date_published") val datePublished: String = "",
)

@Serializable
data class MrProject(val id: String, val slug: String = "", val title: String = "", @SerialName("project_type") val projectType: String = "mod")

@Serializable
private data class MrSearch(val hits: List<MrHit> = emptyList())

@Serializable
private data class MrHit(@SerialName("project_id") val projectId: String, val slug: String = "", val title: String = "")

class ModrinthClient(private val http: Http, private val base: String = "https://api.modrinth.com/v2") {
    suspend fun versionFromSha1(sha1: String): MrVersion? = notFoundAsNull {
        http.json.decodeFromString(MrVersion.serializer(), http.get("$base/version_file/$sha1?algorithm=sha1"))
    }

    suspend fun project(idOrSlug: String): MrProject? = notFoundAsNull {
        http.json.decodeFromString(MrProject.serializer(), http.get("$base/project/${Http.enc(idOrSlug)}"))
    }

    suspend fun versions(projectId: String, profile: GameProfile): List<MrVersion> {
        val loaders = loadersFor(profile.loader)
        val mc = profile.mcVersion ?: return emptyList()
        val q = "loaders=" + Http.enc(loaders.joinToString(",", "[", "]") { "\"$it\"" }) + "&game_versions=" + Http.enc("[\"$mc\"]")
        val list = notFoundAsNull { http.json.decodeFromString(ListSerializer(MrVersion.serializer()), http.get("$base/project/${Http.enc(projectId)}/version?$q")) } ?: emptyList()
        return list.sortedWith(compareBy<MrVersion> { rank(it.versionType) }.thenByDescending { it.datePublished })
    }

    suspend fun search(query: String, profile: GameProfile): List<MrProject> {
        val mc = profile.mcVersion ?: return emptyList()
        val loaderFacet = loadersFor(profile.loader).joinToString(",") { "\"categories:$it\"" }
        val facets = "[[${loaderFacet}],[\"versions:$mc\"],[\"project_type:mod\"]]"
        val text = http.get("$base/search?limit=5&query=${Http.enc(query)}&facets=${Http.enc(facets)}")
        return http.json.decodeFromString(MrSearch.serializer(), text).hits.map { MrProject(it.projectId, it.slug, it.title) }
    }

    companion object {
        fun loadersFor(loader: Loader): List<String> = when (loader) {
            Loader.FORGE, Loader.FORGE_LEGACY -> listOf("forge")
            Loader.NEOFORGE -> listOf("neoforge", "forge")
            Loader.FABRIC -> listOf("fabric")
            Loader.QUILT -> listOf("quilt", "fabric")
            else -> listOf("forge", "fabric", "neoforge", "quilt")
        }

        private fun rank(type: String) = when (type) { "release" -> 0; "beta" -> 1; else -> 2 }

        fun toRemote(v: MrVersion, slug: String?): RemoteFile? {
            val f = v.files.firstOrNull { it.primary } ?: v.files.firstOrNull { it.filename.endsWith(".jar") } ?: return null
            return RemoteFile(RemoteSource.MODRINTH, f.filename, f.url, f.hashes["sha1"], f.hashes["sha512"], v.projectId, v.id, v.versionNumber,
                "https://modrinth.com/mod/${slug ?: v.projectId}")
        }
    }
}

suspend fun <T> notFoundAsNull(block: suspend () -> T): T? = try {
    block()
} catch (e: HttpStatusException) {
    if (e.status == 404) null else throw e
}

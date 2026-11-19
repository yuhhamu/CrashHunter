package dev.crashhunter.core.fix.remote

import dev.crashhunter.core.analyze.StaticChecker
import dev.crashhunter.core.fix.FetchedFile
import dev.crashhunter.core.fix.FetchRules
import dev.crashhunter.core.fix.RemoteFetcher
import dev.crashhunter.core.fix.RemoteFile
import dev.crashhunter.core.fix.RemoteResolver
import dev.crashhunter.core.fix.RemoteSource
import dev.crashhunter.core.fix.Resolved
import dev.crashhunter.core.fix.VersionEntry
import dev.crashhunter.core.fix.VersionList
import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.model.Loader
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModJar
import dev.crashhunter.core.mods.ModMetadataReader
import dev.crashhunter.core.mods.Versions
import kotlinx.serialization.Serializable
import java.io.File

class RemoteResolverImpl(
    private val modrinth: ModrinthClient,
    private val curseforge: CurseForgeClient?,
    private val keys: CurseForgeKeys?,
    private val meta: PrismMetaClient,
    private val maxCandidates: Int = 5,
    private val repairCandidates: Int = 12,
) : RemoteResolver {
    private val cfReady get() = curseforge != null && keys?.available() == true

    override suspend fun forMissing(modId: String, range: String?, instance: GameInstance): Resolved? {
        val profile = instance.profile
        val slugs = listOf(modId, modId.replace('_', '-')).distinct()
        for (slug in slugs) {
            val p = runCatching { modrinth.project(slug) }.getOrNull() ?: continue
            if (p.projectType != "mod") continue
            val c = modrinth.versions(p.id, profile).mapNotNull { ModrinthClient.toRemote(it, p.slug) }.take(maxCandidates)
            if (c.isNotEmpty()) return Resolved(c)
        }
        val hit = runCatching { modrinth.search(modId, profile) }.getOrDefault(emptyList())
            .firstOrNull { h -> slugs.any { it.equals(h.slug, true) } || h.title.replace(" ", "").equals(modId.replace("_", ""), true) }
        if (hit != null) {
            val c = modrinth.versions(hit.id, profile).mapNotNull { ModrinthClient.toRemote(it, hit.slug) }.take(maxCandidates)
            if (c.isNotEmpty()) return Resolved(c)
        }
        if (cfReady) {
            for (slug in slugs) {
                val mod = runCatching { curseforge!!.bySlug(slug) }.getOrNull() ?: continue
                return cfCandidates(mod.id, mod, profile, exclude = null)
            }
        }
        return null
    }

    override suspend fun forUpdate(jar: ModJar, modId: String, range: String?, instance: GameInstance): Resolved? = sameProject(jar, instance)

    override suspend fun forReplace(jar: ModJar, instance: GameInstance): Resolved? = sameProject(jar, instance)

    override suspend fun forRepair(jar: ModJar, instance: GameInstance): Resolved? = sameProject(jar, instance, repairCandidates)

    private suspend fun sameProject(jar: ModJar, instance: GameInstance, limit: Int = maxCandidates): Resolved? {
        val profile = instance.profile
        val sha1 = runCatching { Http.hash(jar.file, "SHA-1") }.getOrNull()
        val entry = PrismModIndex.forFile(instance.modsDir, jar.fileName)
        val mrProject = (sha1?.let { runCatching { modrinth.versionFromSha1(it) }.getOrNull()?.projectId }) ?: entry?.modrinthId
        if (mrProject != null) {
            val c = modrinth.versions(mrProject, profile).mapNotNull { ModrinthClient.toRemote(it, null) }.filter { it.sha1 != sha1 }.take(limit)
            if (c.isNotEmpty()) return Resolved(c)
        }
        var cfProject = entry?.curseProject
        if (cfReady) {
            if (cfProject == null) cfProject = runCatching { curseforge!!.fingerprintMatch(jar.file) }.getOrNull()?.second?.modId
            if (cfProject != null) {
                val mod = try { curseforge!!.mod(cfProject) } catch (e: HttpStatusException) {
                    if (e.status == 401 || e.status == 403) return Resolved(emptyList(), "https://www.curseforge.com/projects/$cfProject", "cfKeyRejected")
                    null
                } catch (e: Exception) { null }
                return cfCandidates(cfProject, mod, profile, exclude = sha1, limit = limit)
            }
        }
        if (entry?.curseProject != null && entry.curseFile != null && jar.readError != null) {
            return Resolved(listOf(RemoteFile(RemoteSource.CURSEFORGE_PRISM, jar.fileName, null, entry.sha1, entry.sha512, entry.curseProject.toString(), entry.curseFile.toString(), jar.fileName,
                "https://www.curseforge.com/projects/${entry.curseProject}")))
        }
        if (cfProject != null) return Resolved(emptyList(), "https://www.curseforge.com/projects/$cfProject", if (cfReady) null else "cfNoKey")
        return null
    }

    private suspend fun cfCandidates(modId: Long, mod: CfMod?, profile: GameProfile, exclude: String?, limit: Int = maxCandidates): Resolved {
        val page = CurseForgeClient.pageUrl(mod, modId)
        val files = try { curseforge!!.files(modId, profile) } catch (e: HttpStatusException) {
            return Resolved(emptyList(), page, if (e.status == 401 || e.status == 403) "cfKeyRejected" else null)
        } catch (e: Exception) { emptyList() }
        val usable = files.filter { f -> f.hashes.none { it.algo == 1 && it.value == exclude } }
        val direct = usable.filter { it.downloadUrl != null && mod?.allowModDistribution != false }.take(limit).map { CurseForgeClient.toRemote(it, mod) }
        if (direct.isNotEmpty()) return Resolved(direct, page)
        val viaPrism = usable.take(minOf(limit, 4)).map { f ->
            RemoteFile(RemoteSource.CURSEFORGE_PRISM, f.fileName, null, f.hashes.firstOrNull { it.algo == 1 }?.value, null, modId.toString(), f.id.toString(), f.displayName.ifBlank { f.fileName }, page)
        }
        return if (viaPrism.isEmpty()) Resolved(emptyList(), page) else Resolved(viaPrism, page, "cfBlocked")
    }

    override suspend fun loaderVersion(profile: GameProfile, range: String): String? = meta.loaderVersionFor(profile, range)

    override suspend fun loaderVersions(profile: GameProfile): List<String> = meta.loaderVersions(profile)

    override suspend fun versionList(jar: ModJar, instance: GameInstance): VersionList? {
        if (!cfReady) return VersionList(emptyList(), reason = "cfNoKey")
        val sha1 = runCatching { Http.hash(jar.file, "SHA-1") }.getOrNull()
        val entry = PrismModIndex.forFile(instance.modsDir, jar.fileName)
        val match = if (entry?.curseProject == null) runCatching { curseforge!!.fingerprintMatch(jar.file) }.getOrNull() else null
        val project = entry?.curseProject ?: match?.second?.modId ?: return VersionList(emptyList(), reason = "cfNotFound")
        val mod = runCatching { curseforge!!.mod(project) }.getOrNull()
        val page = CurseForgeClient.pageUrl(mod, project)
        val files = try { curseforge!!.allFiles(project, instance.profile) } catch (e: HttpStatusException) {
            return VersionList(emptyList(), page, if (e.status == 401 || e.status == 403) "cfKeyRejected" else null)
        } catch (e: Exception) { return VersionList(emptyList(), page) }
        val currentId = entry?.curseFile ?: match?.second?.id
        return VersionList(files.map { f ->
            val remote = if (f.downloadUrl != null && mod?.allowModDistribution != false) CurseForgeClient.toRemote(f, mod)
                else RemoteFile(RemoteSource.CURSEFORGE_PRISM, f.fileName, null, f.hashes.firstOrNull { it.algo == 1 }?.value, null, project.toString(), f.id.toString(), f.displayName.ifBlank { f.fileName }, page)
            val current = f.id == currentId || (sha1 != null && f.hashes.any { it.algo == 1 && it.value.equals(sha1, true) })
            VersionEntry(remote, f.fileDate, current, f.releaseType)
        }, page)
    }
}

class RemoteFetcherImpl(
    private val http: Http,
    private val curseforge: CurseForgeClient?,
    private val prismRoute: PrismImportDownloader?,
    private val workDir: File,
    private val allowUnhashed: Boolean = false,
) : RemoteFetcher {
    override suspend fun fetch(candidates: List<RemoteFile>, expectModId: String?, range: String?, instance: GameInstance, rules: FetchRules): FetchedFile {
        var lastError: String? = null
        for (c in candidates) {
            if (!allowUnhashed && c.sha512 == null && c.sha1 == null) { lastError = "${c.fileName}: no hash"; continue }
            val dest = runCatching { File(workDir, "${System.currentTimeMillis()}-${dev.crashhunter.core.fix.SafeNames.jar(c.fileName)}") }
                .getOrElse { lastError = "${c.fileName}: ${it.message}"; continue }
            try {
                when (c.source) {
                    RemoteSource.MODRINTH -> http.download(requireNotNull(c.url), dest)
                    RemoteSource.CURSEFORGE -> requireNotNull(curseforge) { "no CurseForge client" }.download(requireNotNull(c.url), dest)
                    RemoteSource.CURSEFORGE_PRISM -> requireNotNull(prismRoute) { "no Prism route" }.download(instance, c.projectId.toLong(), c.fileId.toLong(), dest)
                }
                val problem = verify(dest, c, expectModId, range, instance, rules)
                if (problem == null) return FetchedFile(dest, c)
                lastError = "${c.fileName}: $problem"
                dest.delete()
            } catch (e: Exception) {
                lastError = "${c.fileName}: ${e.message}"
                dest.delete()
            }
        }
        error(lastError ?: "no candidate")
    }

    private fun verify(file: File, c: RemoteFile, expectModId: String?, range: String?, instance: GameInstance, rules: FetchRules): String? {
        c.sha512?.let { if (!Http.hash(file, "SHA-512").equals(it, true)) return "hash mismatch" }
        if (c.sha512 == null) c.sha1?.let { if (!Http.hash(file, "SHA-1").equals(it, true)) return "hash mismatch" }
        val jar = ModMetadataReader.read(file)
        if (jar.readError != null) return "unreadable jar"
        if (!StaticChecker.loaderFits(jar, instance.profile)) return "wrong loader"
        if (expectModId != null) {
            val ids = jar.mods.map { it.modId.lowercase() } + jar.provides.map { it.lowercase() }
            if (expectModId.lowercase() !in ids) return "mod id mismatch"
            val v = jar.modsFor(instance.profile.loader).firstOrNull { it.modId.equals(expectModId, true) }?.version
            if (v != null && !v.contains("\${")) for (rg in listOfNotNull(range) + rules.ranges) {
                val ok = if (instance.profile.loader == Loader.FABRIC || instance.profile.loader == Loader.QUILT) Versions.satisfiesFabric(v, rg) else Versions.satisfiesMaven(v, rg)
                if (ok == false) return "version $v not in $rg"
            }
        }
        if (rules.needRefmap && jar.missingRefmaps.isNotEmpty()) return "refmap missing"
        val mc = instance.profile.mcVersion
        if (mc != null) for (m in jar.modsFor(instance.profile.loader)) for (d in m.dependencies) if (d.mandatory && d.modId.equals("minecraft", true) && d.range != null) {
            val ok = m.accepts(mc, d.range)
            if (ok == false) return "needs Minecraft ${d.range}"
        }
        return null
    }
}

@Serializable
private data class MetaRequire(val uid: String, val equals: String? = null)

@Serializable
private data class MetaVersion(val version: String, val recommended: Boolean = false, val requires: List<MetaRequire> = emptyList())

@Serializable
private data class MetaIndex(val versions: List<MetaVersion> = emptyList())

class PrismMetaClient(private val http: Http, private val base: String = "https://meta.prismlauncher.org/v1") {
    suspend fun loaderVersions(profile: GameProfile): List<String> {
        val uid = dev.crashhunter.core.fix.FixPlanner.loaderUid(profile.loader) ?: return emptyList()
        val mc = profile.mcVersion ?: return emptyList()
        val idx = http.json.decodeFromString(MetaIndex.serializer(), http.get("$base/$uid/index.json"))
        val fabricLike = profile.loader == Loader.FABRIC || profile.loader == Loader.QUILT
        return idx.versions.filter { v -> fabricLike || v.requires.any { it.uid == "net.minecraft" && it.equals == mc } }
            .map { it.version }.sortedWith { a, b -> Versions.compare(b, a) }
    }

    suspend fun loaderVersionFor(profile: GameProfile, range: String): String? {
        val uid = dev.crashhunter.core.fix.FixPlanner.loaderUid(profile.loader) ?: return null
        val mc = profile.mcVersion ?: return null
        val idx = http.json.decodeFromString(MetaIndex.serializer(), http.get("$base/$uid/index.json"))
        val fabricLike = profile.loader == Loader.FABRIC || profile.loader == Loader.QUILT
        val fits = idx.versions.filter { v -> fabricLike || v.requires.any { it.uid == "net.minecraft" && it.equals == mc } }
            .filter { v -> (if (fabricLike) Versions.satisfiesFabric(v.version, range) else Versions.satisfiesMaven(v.version, range)) == true }
        val current = profile.loaderVersion
        val newer = fits.filter { current == null || Versions.compare(it.version, current) > 0 }
        return (newer.filter { it.recommended }.ifEmpty { newer }).maxWithOrNull { a, b -> Versions.compare(a.version, b.version) }?.version
    }
}

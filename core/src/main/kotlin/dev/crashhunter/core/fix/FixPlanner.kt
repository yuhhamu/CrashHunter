package dev.crashhunter.core.fix

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.Diagnosis
import dev.crashhunter.core.analyze.Finding
import dev.crashhunter.core.analyze.StaticChecker
import dev.crashhunter.core.model.GameProfile
import dev.crashhunter.core.model.Loader
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar
import dev.crashhunter.core.mods.Versions
import dev.crashhunter.core.prism.IniFile
import java.io.File

data class Resolved(val candidates: List<RemoteFile>, val manualUrl: String? = null, val reason: String? = null)

data class VersionEntry(val remote: RemoteFile, val date: String, val current: Boolean, val releaseType: Int = 1)

data class VersionList(val entries: List<VersionEntry>, val pageUrl: String? = null, val reason: String? = null)

interface RemoteResolver {
    suspend fun forMissing(modId: String, range: String?, instance: GameInstance): Resolved?
    suspend fun forUpdate(jar: ModJar, modId: String, range: String?, instance: GameInstance): Resolved?
    suspend fun forReplace(jar: ModJar, instance: GameInstance): Resolved?
    suspend fun forRepair(jar: ModJar, instance: GameInstance): Resolved? = forReplace(jar, instance)
    suspend fun loaderVersion(profile: GameProfile, range: String): String?
    suspend fun versionList(jar: ModJar, instance: GameInstance): VersionList? = null
    suspend fun loaderVersions(profile: GameProfile): List<String> = emptyList()
}

data class JavaInstall(val path: File, val major: Int)

class FixEnvironment(
    val totalMemoryMb: Long,
    val prismDataDir: File?,
    val javaRoots: List<File>,
) {
    fun javas(): List<JavaInstall> {
        val homes = javaRoots.flatMap { r -> (r.listFiles { f -> f.isDirectory } ?: emptyArray()).toList() + r }
        return homes.mapNotNull { home ->
            val release = File(home, "release").takeIf { it.isFile } ?: return@mapNotNull null
            val v = IniFile.read(release)["JAVA_VERSION"] ?: return@mapNotNull null
            val major = javaMajor(v) ?: return@mapNotNull null
            val bin = listOf("bin/javaw.exe", "bin/java.exe", "bin/java").map { File(home, it) }.firstOrNull { it.isFile } ?: return@mapNotNull null
            JavaInstall(bin, major)
        }.distinctBy { it.path.absolutePath.lowercase() }
    }

    fun globalMaxMem(): Int? = dev.crashhunter.core.prism.PrismFormat.globalMaxMem(prismDataDir)

    companion object {
        fun javaMajor(v: String): Int? {
            val s = v.trim().removePrefix("\"").removeSuffix("\"")
            val parts = s.split('.', '_', '+', '-')
            val first = parts.firstOrNull()?.toIntOrNull() ?: return null
            return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
        }

        fun system(prismDataDir: File?): FixEnvironment {
            val mem = runCatching {
                (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize / (1024 * 1024)
            }.getOrDefault(8192L)
            val roots = mutableListOf<File>()
            prismDataDir?.let { roots += File(it, "java") }
            for (base in listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))) {
                for (vendor in listOf("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto")) roots += File(base, vendor)
            }
            return FixEnvironment(mem, prismDataDir, roots)
        }

        fun javaForMinecraft(mc: String?): Int? {
            mc ?: return null
            val p = mc.split('.').mapNotNull { it.toIntOrNull() }
            if (p.isEmpty()) return null
            if (p[0] >= 26) return 25
            if (p[0] != 1 || p.size < 2) return null
            val minor = p[1]
            val patch = p.getOrElse(2) { 0 }
            return when {
                minor <= 16 -> 8
                minor == 17 -> 16
                minor < 20 || (minor == 20 && patch < 5) -> 17
                else -> 21
            }
        }
    }
}

class FixPlanner(
    private val enabled: Set<FixKind>,
    private val env: FixEnvironment,
    private val resolver: RemoteResolver? = null,
    private val knowledge: ((ModIndex, GameProfile) -> List<Finding>)? = null,
) {
    suspend fun plan(instance: GameInstance, index: ModIndex, diagnoses: List<Diagnosis>, culprits: List<String> = emptyList()): List<FixProposal> {
        val out = mutableListOf<FixProposal>()
        val handledJars = mutableSetOf<String>()
        val known = runCatching { knowledge?.invoke(index, instance.profile) }.getOrNull() ?: emptyList()
        val dbTargets = known.filter { it.cause == Cause.KNOWN_FIX }.groupBy({ it.jar }, { it.other })
        val static = StaticChecker.check(index, instance.profile) + known.filter { it.cause != Cause.KNOWN_FIX }
        val findings = (static + diagnoses.flatMap { it.findings }).distinctBy { "${it.cause}|${jarOf(it, index) ?: it.mod}|${it.other}" }
        val causes = diagnoses.map { it.cause }.toSet() + findings.map { it.cause }

        for (f in findings.filter { it.cause == Cause.DUPLICATE_MOD }) {
            if (FixKind.DUPLICATE !in enabled) break
            val jars = duplicateJars(f, index).filter { it.enabled }
            if (jars.size < 2) continue
            val profile = instance.profile
            val keep = jars.sortedWith(compareBy<ModJar>({ duplicateFit(it, profile) }, { dependentsSatisfied(it, index, profile.loader) },
                { it.modsFor(profile.loader).firstOrNull()?.version?.let { v -> VersionKey(v) } }, { it.file.lastModified() })).last()
            val drop = jars.filter { it != keep && it.fileName !in handledJars }
            if (drop.isEmpty()) continue
            handledJars += drop.map { it.fileName }
            val decisive = drop.all { duplicateFit(it, profile) < duplicateFit(keep, profile) }
            val sameName = jars.map { it.displayName.trim().lowercase() }.distinct().size == 1
            out += FixProposal("DUPLICATE:${keep.fileName}", FixKind.DUPLICATE, keep.displayName,
                mapOf("mod" to keep.displayName, "keep" to keep.fileName, "files" to drop.joinToString(", ") { it.fileName }, "auto" to if (decisive || sameName) "1" else "0"),
                drop.map { DisableMod(it.fileName) })
        }

        for (f in findings.filter { it.cause == Cause.CRASH_TOOL }) {
            if (FixKind.CRASH_TOOL !in enabled) break
            val jarName = jarOf(f, index) ?: continue
            val jar = index.byFileName(jarName)?.takeIf { it.enabled && jarName !in handledJars } ?: continue
            handledJars += jarName
            out += FixProposal("CRASH_TOOL:$jarName", FixKind.CRASH_TOOL, jar.displayName, mapOf("mod" to jar.displayName, "file" to jarName), listOf(DisableMod(jarName)))
        }

        val brokenKinds = mapOf(Cause.WRONG_LOADER to FixKind.WRONG_LOADER, Cause.MC_VERSION to FixKind.MC_MISMATCH, Cause.CORRUPT_JAR to FixKind.CORRUPT_JAR, Cause.MIXIN_NO_REFMAP to FixKind.CORRUPT_JAR, Cause.KNOWN_BROKEN to FixKind.CORRUPT_JAR)
        val connector = index.byModId("connector") != null
        for (f in findings.filter { it.cause in brokenKinds }) {
            val jarName = jarOf(f, index) ?: continue
            if (jarName in handledJars) continue
            val jar = index.byFileName(jarName)?.takeIf { it.enabled } ?: continue
            if (f.cause == Cause.WRONG_LOADER && connector && Loader.FABRIC in jar.loaders && instance.profile.loader in setOf(Loader.FORGE, Loader.NEOFORGE)) continue
            if (f.cause in setOf(Cause.CORRUPT_JAR, Cause.MIXIN_NO_REFMAP, Cause.KNOWN_BROKEN) && FixKind.REPLACE_BROKEN in enabled && resolver != null) {
                val r = runCatching { resolver!!.forRepair(jar, instance) }.getOrNull()
                if (r != null && r.candidates.isNotEmpty()) {
                    handledJars += jarName
                    val preferred = dbTargets[jarName].orEmpty().filterNotNull()
                    val ordered = r.candidates.sortedBy { c -> if (preferred.any { v -> c.versionLabel.contains(v) || c.fileName.contains(v) }) 0 else 1 }
                    val fromDb = f.line == "db" || preferred.isNotEmpty()
                    out += FixProposal("REPAIR:$jarName", FixKind.REPLACE_BROKEN, jar.displayName,
                        mapOf("mod" to jar.displayName, "file" to jarName, "reason" to (r.reason ?: "")) + (if (fromDb) mapOf("db" to "1") else emptyMap()),
                        listOf(ReplaceMod(jarName, ordered, jar.primaryId, null, dependentRanges(jar, index, instance.profile.loader), f.cause == Cause.MIXIN_NO_REFMAP || (f.cause == Cause.KNOWN_BROKEN && f.other == "NO_REFMAP"))), r.manualUrl)
                    continue
                }
                if (r?.manualUrl != null) out += FixProposal("REPAIR:$jarName", FixKind.REPLACE_BROKEN, jar.displayName,
                    mapOf("mod" to jar.displayName, "file" to jarName, "reason" to (r.reason ?: "")), emptyList(), r.manualUrl)
            }
            if (FixKind.REPLACE_VERSION in enabled && resolver != null) {
                val r = runCatching { resolver!!.forReplace(jar, instance) }.getOrNull()
                if (r != null && r.candidates.isNotEmpty()) {
                    handledJars += jarName
                    out += FixProposal("REPLACE:$jarName", FixKind.REPLACE_VERSION, jar.displayName,
                        mapOf("mod" to jar.displayName, "file" to jarName, "new" to r.candidates.first().fileName, "source" to r.candidates.first().source.name),
                        listOf(ReplaceMod(jarName, r.candidates, jar.primaryId)))
                    continue
                }
            }
            val kind = brokenKinds.getValue(f.cause)
            if (kind !in enabled) continue
            handledJars += jarName
            out += FixProposal("${kind.name}:$jarName", kind, jar.displayName,
                mapOf("mod" to jar.displayName, "file" to jarName, "required" to (f.required ?: ""), "actual" to (f.actual ?: "")) + (if (f.line == "db") mapOf("db" to "1") else emptyMap()),
                listOf(DisableMod(jarName)))
        }

        if (FixKind.DB_FIX in enabled && resolver != null && diagnoses.any { it.cause != Cause.NO_PROBLEM }) {
            for ((jarName, targets) in dbTargets) {
                if (jarName == null || jarName in handledJars) continue
                val jar = index.byFileName(jarName)?.takeIf { it.enabled } ?: continue
                val wanted = targets.filterNotNull()
                val r = runCatching { resolver!!.forRepair(jar, instance) }.getOrNull() ?: continue
                val matching = r.candidates.filter { c -> wanted.any { v -> c.versionLabel.contains(v) || c.fileName.contains(v) } }
                if (matching.isEmpty()) continue
                handledJars += jarName
                out += FixProposal("DBFIX:$jarName", FixKind.DB_FIX, jar.displayName,
                    mapOf("mod" to jar.displayName, "file" to jarName, "new" to matching.first().fileName, "to" to wanted.first(), "db" to "1"),
                    listOf(ReplaceMod(jarName, matching, jar.primaryId, null, dependentRanges(jar, index, instance.profile.loader))), r.manualUrl)
            }
        }

        if (FixKind.ENABLE_PROVIDER in enabled) {
            for (f in findings.filter { it.cause == Cause.CLASS_MISMATCH && it.other?.contains('.') == true }.distinctBy { it.other }) {
                val cls = f.other!!
                val entry = cls.replace('.', '/') + ".class"
                val provider = index.jars.filter { !it.enabled }.firstOrNull { j -> runCatching { java.util.zip.ZipFile(j.file).use { it.getEntry(entry) != null } }.getOrDefault(false) } ?: continue
                if (provider.fileName in handledJars) continue
                val ids = provider.mods.map { it.modId.lowercase() }.toSet()
                val conflicts = index.jars.filter { j -> j.enabled && j.mods.any { it.modId.lowercase() in ids } }
                handledJars += provider.fileName
                handledJars += conflicts.map { it.fileName }
                out += FixProposal("ENABLE:${provider.fileName}", FixKind.ENABLE_PROVIDER, provider.displayName,
                    mapOf("mod" to provider.displayName, "file" to provider.fileName, "class" to cls.substringAfterLast('.'), "by" to (f.mod?.let { m -> index.byModId(m)?.displayName ?: m } ?: provider.displayName),
                        "off" to conflicts.joinToString(", ") { it.fileName }),
                    listOf(EnableMod(provider.file.name)) + conflicts.map { DisableMod(it.fileName) })
            }
        }

        if (FixKind.ADD_DEPENDENCY in enabled && resolver != null) {
            for (f in findings.filter { it.cause == Cause.MISSING_DEPENDENCY && it.other != null }.distinctBy { it.other!!.lowercase() }) {
                val id = f.other!!
                if (index.byModId(id)?.enabled == true) continue
                val r = runCatching { resolver!!.forMissing(id, f.required, instance) }.getOrNull() ?: continue
                if (r.candidates.isEmpty() && r.manualUrl == null) continue
                out += FixProposal("ADD:$id", FixKind.ADD_DEPENDENCY, id,
                    mapOf("mod" to id, "by" to (f.mod ?: ""), "new" to (r.candidates.firstOrNull()?.fileName ?: ""), "required" to (f.required ?: "")),
                    if (r.candidates.isEmpty()) emptyList() else listOf(AddMod(r.candidates, id, f.required)), r.manualUrl)
            }
        }

        if (FixKind.UPDATE_DEPENDENCY in enabled && resolver != null) {
            for (f in findings.filter { it.cause == Cause.DEPENDENCY_VERSION }) {
                val jar = (f.jar?.let { index.byFileName(it) } ?: f.other?.let { o -> index.byModId(o) ?: index.jars.firstOrNull { it.displayName == o } })?.takeIf { it.enabled } ?: continue
                if (jar.fileName in handledJars) continue
                val modId = jar.mods.firstOrNull { it.name == f.other || it.modId.equals(f.other, true) }?.modId ?: jar.primaryId ?: continue
                val r = runCatching { resolver!!.forUpdate(jar, modId, f.required, instance) }.getOrNull() ?: continue
                if (r.candidates.isEmpty() && r.manualUrl == null) continue
                handledJars += jar.fileName
                out += FixProposal("UPDATE:${jar.fileName}", FixKind.UPDATE_DEPENDENCY, jar.displayName,
                    mapOf("mod" to jar.displayName, "file" to jar.fileName, "new" to (r.candidates.firstOrNull()?.fileName ?: ""), "required" to (f.required ?: ""), "by" to (f.mod ?: "")),
                    if (r.candidates.isEmpty()) emptyList() else listOf(ReplaceMod(jar.fileName, r.candidates, modId, f.required)), r.manualUrl)
            }
        }

        if (FixKind.LOADER_UPDATE in enabled && resolver != null) {
            val f = findings.firstOrNull { it.cause == Cause.LOADER_VERSION && it.required != null }
            val uid = loaderUid(instance.profile.loader)
            if (f != null && uid != null) {
                val v = runCatching { resolver!!.loaderVersion(instance.profile, f.required!!) }.getOrNull()
                if (v != null && v != instance.profile.loaderVersion) {
                    out += FixProposal("LOADER:$uid", FixKind.LOADER_UPDATE, instance.profile.loader.display,
                        mapOf("loader" to instance.profile.loader.display, "from" to (instance.profile.loaderVersion ?: ""), "to" to v, "by" to (f.mod ?: "")),
                        listOf(SetComponentVersion(uid, v)))
                }
            }
        }

        if (FixKind.MEMORY in enabled && Cause.OUT_OF_MEMORY in causes) memoryProposal(instance)?.let { out += it }
        if (FixKind.JAVA in enabled && Cause.JAVA_VERSION in causes) javaProposal(instance, findings)?.let { out += it }

        if (FixKind.CONFIG_RESET in enabled) {
            for (f in findings.filter { it.cause == Cause.CONFIG_ERROR && !it.other.isNullOrBlank() }.distinctBy { it.other }) {
                val configDir = File(instance.gameDir, "config")
                val hits = configDir.walkTopDown().maxDepth(4).filter { it.isFile && it.name.equals(f.other, true) }.toList()
                val hit = hits.singleOrNull() ?: continue
                val rel = hit.relativeTo(instance.gameDir).invariantSeparatorsPath
                out += FixProposal("CONFIG:$rel", FixKind.CONFIG_RESET, hit.name, mapOf("file" to rel), listOf(ResetConfig(rel)))
            }
        }

        if (FixKind.CULPRIT in enabled && culprits.isNotEmpty()) {
            val names = culprits.filter { index.byFileName(it)?.enabled == true && it !in handledJars }
            if (names.isNotEmpty()) out += FixProposal("CULPRIT:${names.joinToString(",")}", FixKind.CULPRIT, names.joinToString(", ") { index.byFileName(it)?.displayName ?: it },
                mapOf("mod" to names.joinToString(", ") { index.byFileName(it)?.displayName ?: it }, "files" to names.joinToString(", ")),
                names.map { DisableMod(it) })
        }
        return out
    }

    private fun dependentRanges(jar: ModJar, index: ModIndex, loader: Loader): List<String> = rangesRequiredBy(jar, index, loader)

    private fun jarOf(f: Finding, index: ModIndex): String? =
        f.jar ?: f.other?.takeIf { it.endsWith(".jar", true) }?.let { index.byFileName(it)?.fileName }
            ?: f.mod?.let { m -> (index.byModId(m) ?: index.jars.firstOrNull { it.displayName == m })?.fileName }

    private fun duplicateJars(f: Finding, index: ModIndex): List<ModJar> {
        val byFiles = f.other?.split(',')?.map { it.trim() }?.filter { it.endsWith(".jar", true) }?.mapNotNull { index.byFileName(it) }.orEmpty()
        if (byFiles.size >= 2) return byFiles.distinct()
        val id = f.mod ?: return emptyList()
        return index.jars.filter { j -> j.mods.any { it.modId.equals(id, true) || it.name == id } }
    }

    private fun memoryProposal(instance: GameInstance): FixProposal? {
        val cfg = dev.crashhunter.core.launcher.Formats.of(instance).let { f -> { k: String -> f.value(instance, k) } }
        val override = cfg("OverrideMemory")?.equals("true", true) == true
        val current = (if (override) cfg("MaxMemAlloc")?.toIntOrNull() else null) ?: env.globalMaxMem() ?: 4096
        val cap = ((env.totalMemoryMb * 0.6).toLong() / 512 * 512).toInt()
        val wanted = (maxOf(current + 2048, (current * 1.5).toInt()) + 511) / 512 * 512
        val target = minOf(wanted, cap)
        if (target <= current) return null
        val actions = mutableListOf<FixAction>(SetInstanceValue("OverrideMemory", "true"), SetInstanceValue("MaxMemAlloc", target.toString()))
        if (!override) actions += SetInstanceValue("MinMemAlloc", "512")
        return FixProposal("MEMORY", FixKind.MEMORY, instance.name, mapOf("from" to current.toString(), "to" to target.toString()), actions)
    }

    private fun javaProposal(instance: GameInstance, findings: List<Finding>): FixProposal? {
        val required = findings.filter { it.cause == Cause.JAVA_VERSION }.firstNotNullOfOrNull { f -> f.required?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } }
            ?.let { if (it == 1) null else it }
            ?: FixEnvironment.javaForMinecraft(instance.profile.mcVersion) ?: return null
        val cfg = dev.crashhunter.core.launcher.Formats.of(instance).let { f -> { k: String -> f.value(instance, k) } }
        val currentPath = cfg("JavaPath").orEmpty()
        val match = env.javas().filter { it.major == required }.firstOrNull { !it.path.absolutePath.equals(currentPath, true) }
        return if (match != null) {
            FixProposal("JAVA", FixKind.JAVA, instance.name, mapOf("java" to required.toString(), "path" to match.path.absolutePath),
                listOf(SetInstanceValue("OverrideJavaLocation", "true"), SetInstanceValue("JavaPath", match.path.absolutePath.replace('\\', '/'))))
        } else {
            FixProposal("JAVA", FixKind.JAVA, instance.name, mapOf("java" to required.toString(), "path" to ""),
                listOf(SetInstanceValue("OverrideJavaLocation", "true"), SetInstanceValue("AutomaticJava", "true"), SetInstanceValue("IgnoreJavaCompatibility", "false"), SetInstanceValue("JavaPath", "")))
        }
    }

    private fun duplicateFit(jar: ModJar, profile: GameProfile): Int {
        var score = 0
        if (StaticChecker.loaderFits(jar, profile)) score += 4
        val mc = profile.mcVersion
        if (mc != null) {
            val ok = jar.modsFor(profile.loader).flatMap { m -> m.dependencies.filter { it.mandatory && it.modId.equals("minecraft", true) && it.range != null }.map { m.accepts(mc, it.range!!) } }
            if (ok.none { it == false }) score += 2
            val named = Regex("""(?<![\d.])1\.\d{1,2}(?:\.\d{1,2})?(?![\d.])""").findAll(jar.fileName.removeSuffix(".jar")).map { it.value }.toList()
            if (mc in named) score += 1 else if (named.isNotEmpty()) score -= 1
        }
        return score
    }

    private fun dependentsSatisfied(jar: ModJar, index: ModIndex, loader: Loader): Int {
        val mods = jar.modsFor(loader)
        val ids = mods.map { it.modId.lowercase() }.toSet()
        return index.jars.filter { it.enabled && it != jar && it.fileName !in setOf(jar.fileName) }
            .flatMap { j -> j.modsFor(loader).flatMap { m -> m.dependencies.filter { d -> d.mandatory && !d.incompatible && d.modId.lowercase() in ids && d.range != null }.map { d -> m to d } } }
            .count { (m, d) -> mods.firstOrNull { it.modId.equals(d.modId, true) }?.version?.let { v -> m.accepts(v, d.range!!) } == true }
    }

    private class VersionKey(val v: String) : Comparable<VersionKey> {
        override fun compareTo(other: VersionKey): Int = Versions.compare(v, other.v)
    }

    companion object {
        fun loaderUid(loader: Loader): String? = when (loader) {
            Loader.FORGE, Loader.FORGE_LEGACY -> "net.minecraftforge"
            Loader.NEOFORGE -> "net.neoforged"
            Loader.FABRIC -> "net.fabricmc.fabric-loader"
            Loader.QUILT -> "org.quiltmc.quilt-loader"
            else -> null
        }
    }
}

fun rangesRequiredBy(jar: ModJar, index: ModIndex, loader: Loader? = null): List<String> {
    val ids = (jar.mods.map { it.modId } + jar.provides).map { it.lowercase() }.toSet()
    return index.jars.filter { it.enabled && it != jar }.flatMap { j -> j.modsFor(loader).flatMap { m -> m.dependencies } }
        .filter { d -> d.mandatory && !d.incompatible && d.modId.lowercase() in ids && d.range != null && d.range != "*" }
        .mapNotNull { it.range }.distinct()
}

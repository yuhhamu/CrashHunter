package dev.crashhunter.core.scan

import dev.crashhunter.core.analyze.StackTraceTools
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.mods.ModJar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

enum class StaticKind(val weight: Double) {
    FINAL_OVERRIDE(3.0),
    MISSING_METHOD(2.5),
    MISSING_CLASS(1.5),
    DUPLICATE_CLASS(1.0),
}

@Serializable
data class StaticFinding(val kind: StaticKind, val file: String, val other: String?, val subject: String, val target: String) {
    val files: List<String> get() = listOfNotNull(file, other).distinct()
    val classes: List<String> get() = listOf(subject, target).map { it.substringBefore('#').replace('/', '.') }.distinct()
}

@Serializable
data class JarUnit(val path: String, val key: String?, val version: String?, val classes: List<ClassInfo>)

@Serializable
data class JarSummary(val version: Int = FORMAT, val units: List<JarUnit>) {
    companion object { const val FORMAT = 1 }
}

class StaticScan(private val cacheDir: File? = null, private val maxFindings: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true }

    fun scan(index: ModIndex): List<StaticFinding> {
        val jars = index.jars.filter { it.enabled }
        val summaries = jars.associate { j -> j.fileName to summary(j) }
        return analyze(jars, summaries)
    }

    fun analyze(jars: List<ModJar>, summaries: Map<String, JarSummary>): List<StaticFinding> {
        class Owned(val info: ClassInfo, val file: String, val nested: Boolean)
        val mixinPkgs = jars.associate { j -> j.fileName to j.mixinPackages.filter { it.isNotEmpty() }.map { it.replace('.', '/') + "/" } }
        val chosen = HashMap<String, Pair<String, JarUnit>>()
        for (j in jars) for (u in summaries[j.fileName]?.units.orEmpty()) {
            if (u.path.isEmpty() || u.key == null) continue
            val prev = chosen[u.key]
            if (prev == null || newer(u.version, prev.second.version)) chosen[u.key] = j.fileName to u
        }
        val classes = HashMap<String, Owned>()
        val duplicates = mutableListOf<StaticFinding>()
        for (j in jars) for (u in summaries[j.fileName]?.units.orEmpty()) {
            if (u.path.isNotEmpty()) {
                val pick = chosen[u.key]
                if (u.key != null && (pick?.first != j.fileName || pick.second.path != u.path)) continue
            }
            for (c in u.classes) {
                val prev = classes[c.name]
                if (prev == null) { classes[c.name] = Owned(c, j.fileName, u.path.isNotEmpty()); continue }
                if (prev.file == j.fileName) continue
                if (prev.nested && u.path.isEmpty()) { classes[c.name] = Owned(c, j.fileName, false); continue }
                if (!prev.nested && u.path.isEmpty() && !c.name.endsWith("package-info") && !c.name.startsWith("META-INF"))
                    duplicates += StaticFinding(StaticKind.DUPLICATE_CLASS, j.fileName, prev.file, c.name, c.name)
            }
        }
        val packages = HashMap<String, MutableSet<String>>()
        for ((name, o) in classes) packages.getOrPut(name.substringBeforeLast('/', "")) { HashSet() } += o.file
        fun mixin(file: String, name: String) = mixinPkgs[file].orEmpty().any { name.startsWith(it) }
        fun platform(name: String) = name == "java/lang/Object" || StackTraceTools.isPlatformClass(name.replace('/', '.'))

        val out = LinkedHashMap<String, StaticFinding>()
        fun add(f: StaticFinding) {
            if (out.size >= maxFindings) return
            out.putIfAbsent("${f.kind}|${f.file}|${f.other}", f)
        }

        fun ancestors(start: String?): List<Owned> {
            val out = mutableListOf<Owned>()
            var cur = start
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                if (platform(cur)) break
                val o = classes[cur] ?: break
                out += o
                cur = o.info.superName
            }
            return out
        }

        fun resolves(owner: String, name: String, desc: String, field: Boolean): Boolean? {
            val queue = ArrayDeque<String>()
            val seen = HashSet<String>()
            queue += owner
            var unknown = false
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (!seen.add(cur)) continue
                if (platform(cur)) { if (cur != "java/lang/Object") unknown = true; continue }
                val o = classes[cur] ?: run { unknown = true; null } ?: continue
                if (field) { if (o.info.fields.any { it == "$name:$desc" }) return true }
                else if (o.info.methods.any { it.name == name && it.desc == desc }) return true
                if (!field && name == "<init>") return false
                o.info.superName?.let { queue += it }
                queue += o.info.interfaces
            }
            if (!field && !unknown && name in OBJECT_METHODS) return null
            return if (unknown) null else false
        }

        for ((name, o) in classes) {
            if (mixin(o.file, name)) continue
            val c = o.info
            val own = c.methods.filter { it.access and (ACC_STATIC or ACC_PRIVATE) == 0 && it.name != "<init>" && it.name != "<clinit>" && it.access and ACC_SYNTHETIC == 0 }
            if (own.isNotEmpty()) for (a in ancestors(c.superName)) {
                if (a.file == o.file) continue
                val finals = a.info.methods.filter { it.access and ACC_FINAL != 0 && it.access and (ACC_STATIC or ACC_PRIVATE) == 0 }
                val hit = own.firstOrNull { m -> finals.any { it.name == m.name && it.desc == m.desc } } ?: continue
                add(StaticFinding(StaticKind.FINAL_OVERRIDE, o.file, a.file, name, "${a.info.name}#${hit.name}${hit.desc}"))
                break
            }
            for (r in c.refs) {
                val target = classes[r.owner] ?: continue
                if (target.file == o.file) continue
                if (resolves(r.owner, r.name, r.desc, r.field) == false)
                    add(StaticFinding(StaticKind.MISSING_METHOD, o.file, target.file, name, "${r.owner}#${r.name}${r.desc}"))
            }
            for (ref in c.classRefs) {
                if (ref in classes || platform(ref)) continue
                val owners = packages[ref.substringBeforeLast('/', "")] ?: continue
                val other = owners.firstOrNull { it != o.file } ?: continue
                add(StaticFinding(StaticKind.MISSING_CLASS, o.file, other, name, ref))
            }
        }
        for (d in duplicates) add(d)
        return out.values.toList()
    }

    fun summary(j: ModJar): JarSummary {
        val hash = if (cacheDir != null) dev.crashhunter.core.db.DbRefs.sha1(j.file) else null
        val cached = hash?.let { File(cacheDir, "$it.json.gz") }
        if (cached != null && cached.isFile) runCatching {
            GZIPInputStream(cached.inputStream()).use { json.decodeFromString(JarSummary.serializer(), it.readBytes().toString(Charsets.UTF_8)) }
        }.getOrNull()?.takeIf { it.version == JarSummary.FORMAT }?.let { return it }
        val s = runCatching { ZipFile(j.file).use { read(it) } }.getOrDefault(JarSummary(units = emptyList()))
        if (cached != null) runCatching {
            cached.parentFile.mkdirs()
            val tmp = File(cached.parentFile, cached.name + ".tmp")
            GZIPOutputStream(tmp.outputStream()).use { it.write(json.encodeToString(JarSummary.serializer(), s).toByteArray()) }
            if (!tmp.renameTo(cached)) { cached.delete(); tmp.renameTo(cached) }
        }
        return s
    }

    private fun read(zip: ZipFile): JarSummary {
        val units = mutableListOf<JarUnit>()
        val top = mutableListOf<ClassInfo>()
        val meta = zip.getEntry("META-INF/jarjar/metadata.json")?.let { e -> zip.getInputStream(e).use { nestedMeta(it.readBytes().toString(Charsets.UTF_8)) } }.orEmpty()
        for (e in zip.entries()) {
            val n = e.name
            if (e.isDirectory) continue
            if (n.endsWith(".class")) {
                if (n.startsWith("META-INF/versions/")) continue
                zip.getInputStream(e).use { ClassFile.parse(it.readBytes()) }?.let { top += it }
            } else if (n.endsWith(".jar") && (n.startsWith("META-INF/jarjar/") || n.startsWith("META-INF/jars/")) && e.size <= MAX_NESTED) {
                val (key, version) = meta[n] ?: guess(n.substringAfterLast('/'))
                runCatching { zip.getInputStream(e).use { units += JarUnit(n, key, version, slim(nestedClasses(it))) } }
            }
        }
        units.add(0, JarUnit("", null, null, slim(top)))
        return JarSummary(units = units)
    }

    private fun nestedClasses(input: InputStream): List<ClassInfo> {
        val out = mutableListOf<ClassInfo>()
        ZipInputStream(input).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(".class") && !e.name.startsWith("META-INF/versions/")) ClassFile.parse(zin.readAllBytes())?.let { out += it }
                e = zin.nextEntry
            }
        }
        return out
    }

    private fun slim(list: List<ClassInfo>): List<ClassInfo> {
        val names = list.map { it.name }.toHashSet()
        fun external(owner: String) = owner !in names && !StackTraceTools.isPlatformClass(owner.replace('/', '.'))
        return list.map { c ->
            c.copy(
                methods = c.methods.filter { it.access and ACC_PRIVATE == 0 },
                refs = c.refs.filter { external(it.owner) },
                classRefs = c.classRefs.filter { external(it) && !it.startsWith("[") },
            )
        }
    }

    private fun nestedMeta(text: String): Map<String, Pair<String?, String?>> = runCatching {
        Json.parseToJsonElement(text).jsonObject["jars"]!!.jsonArray.associate { el ->
            val o = el.jsonObject
            val path = o["path"]!!.jsonPrimitive.content
            val id = o["identifier"]?.jsonObject
            val key = listOfNotNull(id?.get("group")?.jsonPrimitive?.contentOrNull, id?.get("artifact")?.jsonPrimitive?.contentOrNull).joinToString(":").ifEmpty { null }
            val version = o["version"]?.jsonObject?.get("artifactVersion")?.jsonPrimitive?.contentOrNull
            path to (key to version)
        }
    }.getOrDefault(emptyMap())

    companion object {
        private const val MAX_NESTED = 64L * 1024 * 1024
        private val OBJECT_METHODS = setOf("equals", "hashCode", "toString", "getClass", "clone", "finalize", "notify", "notifyAll", "wait")

        fun guess(fileName: String): Pair<String?, String?> {
            val base = fileName.removeSuffix(".jar")
            val m = Regex("""^(.*?)-(\d[\w.+-]*)$""").find(base) ?: return base to null
            return m.groupValues[1] to m.groupValues[2]
        }

        fun newer(a: String?, b: String?): Boolean {
            if (a == null) return false
            if (b == null) return true
            val x = Regex("""\d+""").findAll(a).map { it.value.toLongOrNull() ?: 0 }.toList()
            val y = Regex("""\d+""").findAll(b).map { it.value.toLongOrNull() ?: 0 }.toList()
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { 0 }
                val q = y.getOrElse(i) { 0 }
                if (p != q) return p > q
            }
            return false
        }

        fun fromBytes(bytes: ByteArray): List<ClassInfo> = ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            val out = mutableListOf<ClassInfo>()
            var e = zin.nextEntry
            while (e != null) { if (e.name.endsWith(".class")) ClassFile.parse(zin.readAllBytes())?.let { out += it }; e = zin.nextEntry }
            out
        }
    }
}

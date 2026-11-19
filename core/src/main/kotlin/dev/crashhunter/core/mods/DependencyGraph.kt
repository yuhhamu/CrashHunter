package dev.crashhunter.core.mods

class DependencyGraph(private val index: ModIndex, loader: dev.crashhunter.core.model.Loader? = null) {
    val requires: Map<String, Set<String>>

    init {
        val map = LinkedHashMap<String, Set<String>>()
        for (j in index.jars) {
            val need = linkedSetOf<String>()
            for (m in j.modsFor(loader)) for (d in m.dependencies) {
                if (!d.mandatory || d.incompatible) continue
                val id = d.modId.lowercase()
                if (id in PLATFORM_IDS) continue
                val target = index.byModId(id) ?: continue
                if (target.fileName != j.fileName) need += target.fileName
            }
            map[j.fileName] = need
        }
        requires = map
    }

    fun withRequirements(names: Collection<String>): Set<String> {
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque(names)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (out.add(n)) requires[n]?.forEach { stack.addLast(it) }
        }
        return out
    }

    fun withDependents(names: Collection<String>): Set<String> {
        val out = LinkedHashSet(names)
        var changed = true
        while (changed) {
            changed = false
            for ((jar, req) in requires) if (jar !in out && req.any { it in out }) { out += jar; changed = true }
        }
        return out
    }
}

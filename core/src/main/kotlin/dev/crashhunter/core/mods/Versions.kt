package dev.crashhunter.core.mods

object Versions {
    private val splitter = Regex("[.\\-+_]")

    fun compare(a: String, b: String): Int {
        val pa = a.trim().split(splitter)
        val pb = b.trim().split(splitter)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return if (pb.drop(i).all { it == "0" || it.isEmpty() }) 0 else -1
            val y = pb.getOrNull(i) ?: return if (pa.drop(i).all { it == "0" || it.isEmpty() }) 0 else 1
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val c = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> 1
                ny != null -> -1
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return 0
    }

    fun satisfiesMaven(version: String, range: String): Boolean? {
        val r = range.trim()
        if (r.isEmpty() || r == "*" || r.contains("\${")) return null
        if (!r.startsWith("[") && !r.startsWith("(")) return compare(version, r) >= 0
        val parts = Regex("""[\[(][^\])]*[\])]""").findAll(r).map { it.value }.toList()
        if (parts.isEmpty()) return null
        return parts.any { p -> one(version, p) ?: return null }
    }

    private fun one(version: String, p: String): Boolean? {
        val lowIncl = p.startsWith("[")
        val highIncl = p.endsWith("]")
        val body = p.substring(1, p.length - 1)
        val comma = body.indexOf(',')
        if (comma < 0) return compare(version, body) == 0
        val lo = body.substring(0, comma).trim()
        val hi = body.substring(comma + 1).trim()
        if (lo.isNotEmpty()) { val c = compare(version, lo); if (c < 0 || (c == 0 && !lowIncl)) return false }
        if (hi.isNotEmpty()) { val c = compare(version, hi); if (c > 0 || (c == 0 && !highIncl)) return false }
        return true
    }

    fun satisfiesFabric(version: String, predicate: String): Boolean? {
        val pred = predicate.trim()
        if (pred.isEmpty() || pred == "*") return null
        for (term in pred.split(Regex("\\s+"))) {
            val ok = fabricTerm(version, term) ?: return null
            if (!ok) return false
        }
        return true
    }

    private fun fabricTerm(v: String, t: String): Boolean? {
        val m = Regex("""^(>=|<=|>|<|=|~|\^)?(.+)$""").matchEntire(t) ?: return null
        val op = m.groupValues[1]
        val target = m.groupValues[2]
        if (target.endsWith(".x") || target.endsWith(".X")) {
            val prefix = target.dropLast(2)
            return v == prefix || v.startsWith("$prefix.")
        }
        val c = compare(v, target)
        val tParts = target.split('.')
        return when (op) {
            ">=" -> c >= 0
            "<=" -> c <= 0
            ">" -> c > 0
            "<" -> c < 0
            "~" -> c >= 0 && sameUpTo(v, target, minOf(2, tParts.size))
            "^" -> c >= 0 && sameUpTo(v, target, 1)
            else -> c == 0
        }
    }

    private fun sameUpTo(a: String, b: String, n: Int): Boolean =
        a.split('.').take(n) == b.split('.').take(n)
}

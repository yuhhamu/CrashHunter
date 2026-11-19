package dev.crashhunter.core.analyze

object RangeText {
    fun humanize(range: String?, lang: String): String? {
        val r = range?.trim()?.takeIf { it.isNotEmpty() && it != "*" } ?: return null
        val ja = lang == "ja"
        fun atLeast(v: String) = if (ja) "$v 以上" else "$v or later"
        fun below(v: String) = if (ja) "$v 未満" else "below $v"
        Regex("""^>=\s*(\S+)$""").matchEntire(r)?.let { return atLeast(it.groupValues[1]) }
        Regex("""^\[([^,\]]+),\s*\)$""").matchEntire(r)?.let { return atLeast(it.groupValues[1].trim()) }
        Regex("""^\[([^,\]]+),\s*([^)\]]+)\)$""").matchEntire(r)?.let {
            return if (ja) "${it.groupValues[1].trim()} 以上 ${it.groupValues[2].trim()} 未満" else "${it.groupValues[1].trim()} to below ${it.groupValues[2].trim()}"
        }
        Regex("""^\(,\s*([^)\]]+)\)$""").matchEntire(r)?.let { return below(it.groupValues[1].trim()) }
        Regex("""^\[([^,\]]+)]$""").matchEntire(r)?.let { return it.groupValues[1].trim() }
        return r
    }
}

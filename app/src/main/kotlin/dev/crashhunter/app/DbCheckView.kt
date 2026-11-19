package dev.crashhunter.app

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.SolutionKb
import dev.crashhunter.core.db.DbCheck
import dev.crashhunter.core.db.DbRefs
import dev.crashhunter.core.db.ModRef
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.mods.ModIndex

class DbCheckResult(val count: Int, val check: DbCheck)

object DbCheckView {
    private val cache = HashMap<String, Pair<Long, DbCheckResult>>()

    suspend fun run(ctx: AppContext, inst: GameInstance, maxAgeMs: Long = 600_000L): DbCheckResult {
        val refs = ModIndex.load(inst.modsDir).jars.filter { it.enabled }.map { DbRefs.of(it) }
        val key = inst.id + "\u0000" + refs.joinToString("|") { it.id + "@" + it.v + "#" + it.sha1 }
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.first < maxAgeMs }?.let { return it.second } }
        val result = DbCheckResult(refs.size, ctx.crashDb.check(inst.profile, refs))
        runCatching { ctx.crashDb.refreshSnapshot(inst.profile, 0) }
        synchronized(cache) { cache[key] = System.currentTimeMillis() to result }
        return result
    }

    fun hasProblems(r: DbCheck) = r.known.broken.isNotEmpty() || r.known.conflicts.isNotEmpty() || r.known.fixes.isNotEmpty() || r.crashes.isNotEmpty()

    private fun ref(m: ModRef) = m.id + (m.v?.let { " $it" } ?: "")

    private fun reason(ctx: AppContext, x: String) = ctx.strings.let { s -> if (s.has("dbcheck.reason.$x")) s["dbcheck.reason.$x"] else x }

    fun headline(ctx: AppContext, r: DbCheck): String {
        val s = ctx.strings
        r.known.broken.firstOrNull()?.let { return ref(it.mod) + ": " + reason(ctx, it.reason) }
        r.known.conflicts.firstOrNull()?.let { return it.mods.joinToString(" + ") { m -> m.id } }
        r.known.fixes.firstOrNull()?.let { return s.t("dbcheck.head.fix", mapOf("mod" to ref(it.from.first()))) }
        r.crashes.firstOrNull()?.let { return s.t("dbcheck.head.crash", mapOf("mod" to ref(it.mod))) }
        return ""
    }

    fun markdown(ctx: AppContext, inst: GameInstance, res: DbCheckResult): String {
        val s = ctx.strings
        val r = res.check
        val kb = SolutionKb.load(ctx.settings.lang)
        fun cause(c: String) = runCatching { kb.render(Cause.valueOf(c), emptyMap()).title }.getOrDefault(c)
        fun fixKind(k: String) = k.split('+').joinToString("・") { x -> if (s.has("dbcheck.fix.$x")) s["dbcheck.fix.$x"] else if (s.has("settings.fix.$x")) s["settings.fix.$x"] else x }
        val k = r.known
        val sb = StringBuilder()
        sb.append("# ").append(s.t("dbcheck.heading", mapOf("name" to inst.name))).append("\n\n")
        sb.append("**").append(if (k.broken.isNotEmpty() || k.conflicts.isNotEmpty()) s["dbcheck.verdict.bad"] else s["dbcheck.verdict.warn"]).append("**\n\n")
        sb.append(s.t("dbcheck.summary", mapOf("env" to inst.profile.label, "n" to res.count.toString()))).append("\n\n")
        if (k.broken.isNotEmpty() || k.conflicts.isNotEmpty() || k.fixes.isNotEmpty()) {
            sb.append("## ").append(s["dbcheck.known"]).append("\n\n")
            for (b in k.broken) sb.append("- ").append(s.t("dbcheck.broken", mapOf("mod" to ref(b.mod), "reason" to reason(ctx, b.reason), "n" to b.n.toString())))
                .append(" ").append(s[if (b.confirmed) "dbcheck.confirmed" else "dbcheck.unconfirmed"]).append("\n")
            for (x in k.conflicts) sb.append("- ").append(s.t("dbcheck.conflict", mapOf("mods" to x.mods.joinToString(" + ") { ref(it) }, "n" to x.n.toString()))).append("\n")
            for (f in k.fixes) sb.append("- ").append(s.t("dbcheck.fixline", mapOf("from" to f.from.joinToString(", ") { ref(it) }, "to" to f.to.joinToString(", ") { ref(it) }, "kind" to fixKind(f.kind), "n" to f.n.toString()))).append("\n")
            if (k.fixes.isNotEmpty()) sb.append("\n").append(s["dbcheck.fixhint"]).append("\n")
            sb.append("\n")
        }
        if (r.crashes.isNotEmpty()) {
            sb.append("## ").append(s["dbcheck.crashes"]).append("\n\n")
            for (x in r.crashes) {
                val causes = x.causes.entries.sortedByDescending { it.value }.joinToString("、") { cause(it.key) }
                sb.append("- ").append(s.t("dbcheck.crashline", mapOf("mod" to ref(x.mod), "n" to x.installs.toString(), "causes" to causes))).append("\n")
            }
            sb.append("\n")
        }
        sb.append("---\n\n").append(s["dbcheck.note"]).append("\n")
        return sb.toString()
    }
}

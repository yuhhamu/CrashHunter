package dev.crashhunter.verifier

import dev.crashhunter.core.db.ModRef
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.verify.LocalSource
import dev.crashhunter.core.verify.ModrinthSource
import dev.crashhunter.core.verify.PendingFile
import dev.crashhunter.core.verify.StaticVerifier
import dev.crashhunter.core.verify.Verdict
import dev.crashhunter.core.verify.VerdictBody
import dev.crashhunter.core.verify.VerifyRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

const val TOOL = "crashhunter-verify/1.0.0"

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

class Options(args: List<String>) {
    val command = args.firstOrNull() ?: "help"
    private val rest = args.drop(1)
    private fun value(name: String) = rest.indexOf(name).takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }
    private fun values(name: String) = rest.indices.filter { rest[it] == name }.mapNotNull { rest.getOrNull(it + 1) }
    val base = value("--base") ?: "https://db.yuhhamu.com/crashhunter"
    val credentials = File(value("--credentials") ?: System.getenv("CH_VERIFY_CREDENTIALS") ?: File(System.getProperty("user.home"), ".crashhunter-verify.env").path)
    val search = values("--search").map(::File)
    val file = value("--file")?.let(::File)
    val all = "--all" in rest
    val dryRun = "--dry-run" in rest
    val confirmManual = "--confirm" in rest
    val rejectManual = "--reject" in rest
    val detail = value("--detail")
    val adminEnv = value("--admin-env")?.let(::File)
    val cache = File(value("--cache") ?: File(System.getProperty("user.home"), ".crashhunter-verify-cache").path)
    val target = rest.withIndex().firstOrNull { (i, a) -> !a.startsWith("--") && (i == 0 || rest[i - 1] !in VALUED) }?.value

    companion object { val VALUED = setOf("--admin-env", "--reason", "--base", "--credentials", "--search", "--file", "--detail", "--cache") }
}

class Api(private val o: Options) {
    private val http = Http(Http.userAgent(TOOL))
    private val admin = o.adminEnv != null
    private val headers: Map<String, String> by lazy {
        o.adminEnv?.let { f ->
            val token = f.takeIf { it.isFile }?.readLines()?.firstNotNullOfOrNull { l -> Regex("^CH_DB_ADMIN_TOKEN=(.+)$").find(l.trim())?.groupValues?.get(1)?.trim() }
                ?: fail("管理用トークンが ${f} にありません")
            return@lazy mapOf("authorization" to "Bearer $token", "content-type" to "application/json")
        }
        val vals = HashMap<String, String>()
        if (o.credentials.isFile) o.credentials.readLines().forEach { l -> Regex("^([A-Z_]+)=(.*)$").find(l.trim())?.let { vals[it.groupValues[1]] = it.groupValues[2].trim() } }
        val id = System.getenv("CF_ACCESS_CLIENT_ID") ?: vals["CF_ACCESS_CLIENT_ID"]
        val secret = System.getenv("CF_ACCESS_CLIENT_SECRET") ?: vals["CF_ACCESS_CLIENT_SECRET"]
        if (id.isNullOrBlank() || secret.isNullOrBlank()) fail("Cloudflare Access のサービストークンがありません。${o.credentials} に CF_ACCESS_CLIENT_ID と CF_ACCESS_CLIENT_SECRET を書いてください")
        mapOf("CF-Access-Client-Id" to id, "CF-Access-Client-Secret" to secret, "content-type" to "application/json")
    }
    private fun url(p: String) = o.base.trimEnd('/') + (if (admin) "/v1/admin/" else "/v1/verify/") + p
    fun whoami(): String = if (admin) { runBlocking { http.get(url("stats"), headers) }; "admin" } else runBlocking { http.get(url("whoami"), headers) }
    fun pending(): List<PendingFile> = json.decodeFromString(ListSerializer(PendingFile.serializer()), runBlocking { http.get(url("pending"), headers) })
    fun send(v: VerdictBody) { runBlocking { http.post(url(if (admin) "verdict" else "result"), json.encodeToString(VerdictBody.serializer(), v), headers) } }
}

fun fail(msg: String): Nothing { System.err.println(msg); exitProcess(1) }

fun ref(m: ModRef) = m.id + (m.v?.let { " $it" } ?: "")

fun main(args: Array<String>) {
    val o = Options(args.toList())
    val api = Api(o)
    when (o.command) {
        "whoami" -> println(api.whoami())
        "inspect" -> {
            val f = o.file ?: o.target?.let(::File) ?: fail("inspect <jar> [--reason NO_REFMAP|BROKEN]")
            val reason = args.toList().let { l -> l.indexOf("--reason").takeIf { it >= 0 }?.let { l.getOrNull(it + 1) } } ?: "NO_REFMAP"
            val out = runBlocking { StaticVerifier.verify(PendingFile(ModRef(f.name), reason), f) }
            println("${f.name} [$reason]: ${out.verdict.name.lowercase()} - ${out.detail}")
        }
        "pending" -> {
            val list = api.pending()
            if (list.isEmpty()) println("確認待ちはありません")
            list.forEach { p -> println("${p.mod.id}@${p.mod.v}  ${p.reason}  ${p.n}台  ${p.envs.joinToString(",")}${p.tried?.let { "  (前回: 判定できず)" } ?: ""}") }
        }
        "verify" -> {
            val list = api.pending()
            val targets = when {
                o.all -> list
                o.target != null -> list.filter { "${it.mod.id}@${it.mod.v}" == o.target }.ifEmpty { fail("確認待ちに ${o.target} はありません") }
                else -> fail("verify には <modid@version> か --all を指定してください")
            }
            if (o.confirmManual || o.rejectManual) {
                if (o.target == null) fail("--confirm / --reject は1件ずつ指定してください")
                val t = targets.single()
                val v = VerdictBody(t.mod, t.reason, if (o.confirmManual) "confirmed" else "rejected", "manual", o.detail, TOOL)
                if (!o.dryRun) api.send(v)
                println("${ref(t.mod)}: ${v.verdict}(手動)${if (o.dryRun) " [送信なし]" else ""}")
                return
            }
            val runner = VerifyRunner(listOf(LocalSource(o.search), ModrinthSource(Http(Http.userAgent(TOOL)))), listOf(StaticVerifier), o.cache)
            for (t in targets) {
                val out = runBlocking { runner.run(t, o.file) }
                val verdict = when (out.verdict) { Verdict.CONFIRMED -> "confirmed"; Verdict.REJECTED -> "rejected"; Verdict.INCONCLUSIVE -> "inconclusive" }
                println("${ref(t.mod)} [${t.reason}]: $verdict - ${out.detail}${if (o.dryRun) " [送信なし]" else ""}")
                if (!o.dryRun) api.send(VerdictBody(t.mod, t.reason, verdict, out.method, out.detail, TOOL))
            }
        }
        else -> println(
            """
            crashhunter-verify <command> [options]
              whoami                       認証の確認
              inspect <jar> [--reason R]   サーバーを使わずに1つのjarを調べる
              pending                      確認待ちの壊れたファイル
              verify <modid@version>       1件を検証して結果を送る
              verify --all                 確認待ちをすべて検証する
              verify <modid@version> --confirm|--reject [--detail 文]   手動で確定・却下
            options:
              --search <フォルダ>   ハッシュが一致するjarを探す場所(複数可)
              --file <jar>          検証するjarを直接指定
              --dry-run             結果を送らない
              --admin-env <file>    管理用トークン(CH_DB_ADMIN_TOKEN)の .env で管理APIを使う(管理ページと同じIP制限)
              --credentials <file>  サービストークンのファイル(既定: ~/.crashhunter-verify.env)
              --base <url>          サーバー(既定: https://db.yuhhamu.com/crashhunter)
            """.trimIndent()
        )
    }
}

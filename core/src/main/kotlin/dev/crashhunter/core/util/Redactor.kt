package dev.crashhunter.core.util

object Redactor {
    private val rules = listOf(
        Regex("""(?i)([A-Z]:[\\/](?:Users|Documents and Settings)[\\/])((?:[^\\/\r\n:*?"<>|]+(?=[\\/]))|[^\\/\s]+)""") to "$1<user>",
        Regex("""(?:\\\\|(?<![:\w])//)[^\\/\s]+[\\/][^\\/\r\n:*?"<>|]+""") to "<share>",
        Regex("""(/(?:home|Users)/)([^/\s]+)""") to "$1<user>",
        Regex("""(?i)(--accessToken\s+)\S+""") to "$1<token>",
        Regex("""(?i)(--uuid\s+)\S+""") to "$1<uuid>",
        Regex("""(?i)(--username\s+)\S+""") to "$1<name>",
        Regex("""(?i)(--xuid\s+|--clientId\s+)\S+""") to "$1<id>",
        Regex("""(Setting user:\s*)\S+""") to "$1<name>",
        Regex("""(?i)(Player Count: \d+ / \d+; \[)[^\]]*""") to "$1<players>",
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""") to "<uuid>",
        Regex("""(?<![\w.\-])(?:\d{1,3}\.){3}\d{1,3}(?![\w.\-])""") to "<ip>",
    )

    fun redact(text: String): String = rules.fold(text) { acc, (re, rep) -> re.replace(acc, rep) }
}

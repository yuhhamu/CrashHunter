package dev.crashhunter.core.util

import java.util.Base64
import java.util.concurrent.TimeUnit

object SecretStore {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun protect(plain: String): String {
        if (plain.isBlank()) return ""
        if (!isWindows) return "b64:" + Base64.getEncoder().encodeToString(plain.toByteArray())
        val out = powershell("ConvertTo-SecureString -String \$env:CH_SECRET -AsPlainText -Force | ConvertFrom-SecureString", plain)
        return "dpapi:" + out.trim()
    }

    fun reveal(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return runCatching {
            when {
                stored.startsWith("b64:") -> String(Base64.getDecoder().decode(stored.removePrefix("b64:")))
                stored.startsWith("dpapi:") && isWindows -> powershell(
                    "\$s = ConvertTo-SecureString \$env:CH_SECRET; [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR(\$s))",
                    stored.removePrefix("dpapi:"),
                ).trim()
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun powershell(script: String, secret: String): String {
        val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true)
        pb.environment()["CH_SECRET"] = secret
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) error("powershell failed: ${out.take(200)}")
        return out
    }
}

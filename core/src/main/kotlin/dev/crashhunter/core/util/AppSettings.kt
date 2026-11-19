package dev.crashhunter.core.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object AppDirs {
    val dataDir: File by lazy {
        val override = System.getProperty("crashhunter.dataDir")
        when {
            override != null -> File(override)
            System.getenv("APPDATA") != null -> File(System.getenv("APPDATA"), "CrashHunter")
            else -> File(System.getProperty("user.home"), ".config/crashhunter")
        }.also { it.mkdirs() }
    }
    val settingsFile get() = File(dataDir, "settings.json")
    val journalFile get() = File(dataDir, "journal.json")
    val userSolutions get() = File(dataDir, "solutions.json")
    val logDir get() = File(dataDir, "logs")
    val gamesFile get() = File(dataDir, "running-games.txt")
    val fixesDir get() = File(dataDir, "fixes")
    val downloadsDir get() = File(dataDir, "downloads")
}

@Serializable
data class AppSettings(
    val prismExe: String? = null,
    val prismDataDir: String? = null,
    val language: String? = null,
    val lastInstance: String? = null,
    val useWorkCopy: Boolean = true,
    val includeSaves: Boolean = false,
    val manualJudge: Boolean = false,
    val startTimeoutSec: Int = 180,
    val timeoutSec: Int = 600,
    val settleSec: Int? = null,
    val repeats: Int = 1,
    val hangIsFailure: Boolean = false,
    val anyCrash: Boolean = false,
    val fixKinds: Map<String, Boolean> = emptyMap(),
    val autoApplyFixes: Boolean = false,
    val verifyAfterFix: Boolean = true,
    val notifyFixes: Boolean = true,
    val fixChainMax: Int = 5,
    val parallelTrials: Int = 0,
    val maxParallel: Int = 3,
    val minimizeGames: Boolean = true,
    val dbUse: Boolean = false,
    val dbSend: Boolean = false,
    val dbConfirmSend: Boolean = true,
    val dbSendRuns: Boolean = false,
    val dbInstallId: String? = null,
    val dbEndpoint: String = "https://db.yuhhamu.com/crashhunter",
    val cfUserKey: String? = null,
    val termsVersion: Int = 0,
    val onboarded: Boolean = false,
    val checkUpdates: Boolean = true,
) {
    val lang: String get() = language ?: if (java.util.Locale.getDefault().language == "ja") "ja" else "en"

    fun save(file: File = AppDirs.settingsFile) {
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(serializer(), this))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        fun load(file: File = AppDirs.settingsFile): AppSettings =
            runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrDefault(AppSettings()).let { s ->
                if (s.dbEndpoint.trimEnd('/') in setOf("https://db.yuhhamu.com", "https://crashhunter.db.yuhhamu.com")) s.copy(dbEndpoint = AppSettings().dbEndpoint) else s
            }
    }
}

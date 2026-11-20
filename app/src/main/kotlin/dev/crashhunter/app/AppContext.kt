package dev.crashhunter.app

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.analyze.CrashSource
import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.bisect.JudgeMode
import dev.crashhunter.core.bisect.TrialSettings
import dev.crashhunter.core.fix.FixApplier
import dev.crashhunter.core.fix.FixEnvironment
import dev.crashhunter.core.fix.FixKind
import dev.crashhunter.core.fix.FixPlanner
import dev.crashhunter.core.fix.FixSession
import dev.crashhunter.core.fix.FixOutcome
import dev.crashhunter.core.fix.FixProposal
import dev.crashhunter.core.fix.Replan
import dev.crashhunter.core.fix.Replanner
import dev.crashhunter.core.analyze.SourceKind
import dev.crashhunter.core.fix.remote.CurseForgeClient
import dev.crashhunter.core.fix.remote.CurseForgeKeys
import dev.crashhunter.core.fix.remote.KeyCheck
import dev.crashhunter.core.fix.remote.Http
import dev.crashhunter.core.fix.remote.ModrinthClient
import dev.crashhunter.core.fix.remote.PrismImportDownloader
import dev.crashhunter.core.fix.remote.PrismMetaClient
import dev.crashhunter.core.fix.remote.RemoteFetcherImpl
import dev.crashhunter.core.fix.remote.RemoteResolverImpl
import dev.crashhunter.core.mods.ModIndex
import dev.crashhunter.core.util.SecretStore
import dev.crashhunter.core.analyze.SolutionKb
import dev.crashhunter.core.model.GameInstance
import dev.crashhunter.core.prism.InstanceScanner
import dev.crashhunter.core.launcher.Launcher
import dev.crashhunter.core.launcher.LauncherInstallation
import dev.crashhunter.core.prism.PrismLauncher
import dev.crashhunter.core.util.AppDirs
import dev.crashhunter.core.util.AppSettings
import java.io.File
import java.util.Locale
import java.util.ResourceBundle

class AppContext {
    var settings: AppSettings = AppSettings.load()
        private set
    var launcher: Launcher? = null
        private set
    val install: LauncherInstallation? get() = launcher?.installation
    var instances: List<GameInstance> = emptyList()
        private set
    lateinit var diagnosis: DiagnosisService
        private set
    lateinit var strings: Strings
        private set

    private val listeners = mutableListOf<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners += l }

    init { reload() }

    fun update(change: (AppSettings) -> AppSettings) {
        val before = settings
        settings = change(settings)
        settings.save()
        if (before.prismExe != settings.prismExe || before.prismDataDir != settings.prismDataDir || before.language != settings.language) {
            reload()
            listeners.forEach { it() }
        }
    }

    fun reload() {
        strings = Strings(settings.lang)
        diagnosis = DiagnosisService(SolutionKb.load(settings.lang, AppDirs.userSolutions)).also { it.knowledge = { idx, prof -> dbKnowledge(idx, prof) } }
        launcher = PrismLauncher.detect(settings.prismExe?.let(::File), settings.prismDataDir?.let(::File))
        instances = launcher?.instances() ?: emptyList()
    }

    @Volatile var bisectRunning = false
    @Volatile var fixRunning = false
    val lastFix = java.util.concurrent.ConcurrentHashMap<String, dev.crashhunter.core.fix.FixOutcome>()

    val http by lazy { Http(Http.userAgent(VERSION)) }
    private val appCfKey: String? by lazy {
        AppContext::class.java.getResourceAsStream("/crashhunter/cf-app.key")?.use { it.readBytes().toString(Charsets.UTF_8).trim() }?.takeIf { it.isNotBlank() }
    }
    @Volatile private var userKeyLoaded = false
    @Volatile private var userKeyCache: String? = null

    fun cfUserKey(): String? {
        if (!userKeyLoaded) { userKeyCache = SecretStore.reveal(settings.cfUserKey); userKeyLoaded = true }
        return userKeyCache
    }

    fun setCfUserKey(plain: String?) {
        val p = plain?.trim()?.takeIf { it.isNotEmpty() }
        val stored = p?.let { SecretStore.protect(it) }
        userKeyCache = p
        userKeyLoaded = true
        update { it.copy(cfUserKey = stored) }
    }

    val hasAppCfKey: Boolean get() = appCfKey != null

    private val dbToken: String? by lazy {
        AppContext::class.java.getResourceAsStream("/crashhunter/db-app.token")?.use { it.readBytes().toString(Charsets.UTF_8).trim() }?.takeIf { it.isNotBlank() }
    }
    val crashDb by lazy { dev.crashhunter.core.db.CrashDb(File(AppDirs.dataDir, "db"), http, { settings.dbEndpoint }, { dbToken }) }

    fun dbKnowledge(index: ModIndex, profile: dev.crashhunter.core.model.GameProfile): List<dev.crashhunter.core.analyze.Finding> =
        if (!settings.dbUse) emptyList() else dev.crashhunter.core.db.CrashDb.knowledge(crashDb.snapshot(profile), index)

    fun installId(): String = settings.dbInstallId ?: java.util.UUID.randomUUID().toString().also { id -> update { it.copy(dbInstallId = id) } }

    fun resetInstallId() = update { it.copy(dbInstallId = java.util.UUID.randomUUID().toString()) }

    suspend fun checkCfKey(typed: String?): KeyCheck = curseforge.checkKey(typed?.trim()?.takeIf { it.isNotEmpty() } ?: cfUserKey() ?: "")
    val cfKeys by lazy { CurseForgeKeys({ appCfKey }, { cfUserKey() }) }
    private val modrinth by lazy { ModrinthClient(http) }
    private val curseforge by lazy { CurseForgeClient(http, cfKeys) }
    private val meta by lazy { PrismMetaClient(http) }

    fun fixPlanner(): FixPlanner = FixPlanner(FixKind.enabledFrom(settings.fixKinds), FixEnvironment.system(install?.dataDir), RemoteResolverImpl(modrinth, curseforge, cfKeys, meta)) { idx, prof -> dbKnowledge(idx, prof) }

    fun resolver(): dev.crashhunter.core.fix.RemoteResolver = RemoteResolverImpl(modrinth, curseforge, cfKeys, meta)

    fun fixPlannerFor(kinds: Set<FixKind>): FixPlanner = FixPlanner(kinds, FixEnvironment.system(install?.dataDir), RemoteResolverImpl(modrinth, curseforge, cfKeys, meta)) { idx, prof -> dbKnowledge(idx, prof) }

    fun tuneApplier(status: (String) -> Unit = {}): FixApplier =
        FixApplier(RemoteFetcherImpl(http, curseforge, PrismImportDownloader({ install?.exe }, { install?.instancesDir }, status = status), AppDirs.downloadsDir), File(AppDirs.dataDir, "tune-fixes"))

    fun fixApplier(status: (String) -> Unit = {}, allowUnhashed: Boolean = false): FixApplier =
        FixApplier(RemoteFetcherImpl(http, curseforge, PrismImportDownloader({ install?.exe }, { install?.instancesDir }, status = status), AppDirs.downloadsDir, allowUnhashed), AppDirs.fixesDir)

    fun fixSession(instance: GameInstance, status: (String) -> Unit, allowUnhashed: Boolean = false): FixSession {
        val l = launcher
        val trials = if (settings.verifyAfterFix && l != null) FixSession.prismTrials(
            l.controller(AppDirs.logDir),
            TrialSettings(JudgeMode.AUTO, settings.startTimeoutSec, settings.timeoutSec, settings.settleSec),
            File(AppDirs.dataDir, "fix-journal.json"),
        ) else null
        val fp: (CrashSource) -> CrashFingerprint? = { src -> diagnosis.diagnose(src, instance, ModIndex.load(instance.modsDir)).fingerprint }
        return FixSession(fixApplier(status, allowUnhashed), trials, fp, status)
    }

    fun replanner(): Replanner = Replanner { instance, outcome ->
        val index = ModIndex.load(instance.modsDir)
        val sources = diagnosis.sources(instance)
        val src = outcome.crashFile?.let { name -> sources.firstOrNull { it.file?.name == name } }
            ?: sources.firstOrNull { it.kind == SourceKind.LATEST_LOG } ?: return@Replanner null
        val d = diagnosis.diagnose(src, instance, index)
        Replan(fixPlanner().plan(instance, index, listOf(d)), d.fingerprint)
    }

    suspend fun runFix(instance: GameInstance, proposals: List<FixProposal>, before: CrashFingerprint?, allowUnhashed: Boolean = false, status: (String) -> Unit): FixOutcome =
        fixSession(instance, status, allowUnhashed).runChain(instance, proposals, before, settings.verifyAfterFix, settings.fixChainMax, replanner())

    fun rescan() {
        instances = launcher?.instances() ?: emptyList()
    }

    fun instancesSignature(): String = instanceSignatures().entries.joinToString("|") { "${it.key}:${it.value}" }

    fun instanceSignatures(): Map<String, String> {
        val l = launcher ?: return emptyMap()
        val dir = l.installation.instancesDir
        val dirs = dir.listFiles { f -> f.isDirectory && !f.name.endsWith(InstanceScanner.WORK_COPY_SUFFIX) && !f.name.endsWith(dev.crashhunter.core.bisect.WorkCopy.STAGING_SUFFIX) && !f.name.startsWith(".") && !f.name.startsWith("CrashHunter-dl-") } ?: return emptyMap()
        val busy = bisectRunning || fixRunning
        return dirs.sortedBy { it.name }.associate { d ->
            val sig = if (busy) "busy" else {
                val mods = (File(d, "minecraft/mods").takeIf { it.isDirectory } ?: File(d, ".minecraft/mods")).lastModified()
                "${l.format.signature(d)}:$mods"
            }
            d.name to sig
        }
    }

}

const val VERSION = "0.1.4"
val VERSION_LABEL: String get() = if (VERSION.startsWith("0.")) "$VERSION beta" else VERSION

class Strings(lang: String) {
    private val bundle = ResourceBundle.getBundle("crashhunter.messages", Locale.forLanguageTag(lang), ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
    operator fun get(key: String): String = if (bundle.containsKey(key)) bundle.getString(key) else key
    fun f(key: String, vararg args: Any?): String = String.format(get(key), *args)
    fun t(key: String, params: Map<String, String>): String = params.entries.fold(get(key)) { s, (k, v) -> s.replace("{$k}", v) }
    fun has(key: String) = bundle.containsKey(key)
}

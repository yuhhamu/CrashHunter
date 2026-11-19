package dev.crashhunter.core

import dev.crashhunter.core.analyze.Cause
import dev.crashhunter.core.analyze.DiagnosisService
import dev.crashhunter.core.analyze.SolutionKb
import dev.crashhunter.core.mods.ModIndex
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixinRefmapTest {
    private val service = DiagnosisService(SolutionKb.load("ja"))

    private val crash = """
        ---- Minecraft Crash Report ----
        Description: Mod loading error has occurred

        java.lang.Exception: Mod Loading has failed

        -- Head --
        Thread: Render thread
        Suspected Mods: NONE
        -- MOD jei --
        Details:
        	Caused by 0: org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical error was encountered
        	Caused by 1: org.spongepowered.asm.mixin.throwables.MixinApplyError: Mixin [emextras.mixins.json:MixinItemTierInstaller] from phase [DEFAULT] in config [emextras.mixins.json] FAILED during APPLY
        	Exception message: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure: @Inject annotation on useOnInject could not find any targets matching 'useOn' in mekanism.common.item.ItemTierInstaller. No refMap loaded. [PREINJECT Applicator Phase -> emextras.mixins.json:MixinItemTierInstaller -> Prepare Injections ->  -> handler]
        -- MOD advanced_ae --
        Details:
        	Caused by 0: java.lang.NoClassDefFoundError: software/bernie/geckolib/animatable/GeoItem
    """.trimIndent()

    @Test
    fun `mixin failure is blamed on the config owner, not on cascading errors`() {
        val d = service.diagnoseText(crash)
        assertEquals(Cause.MIXIN_FAILURE, d.cause, d.detail)
        assertTrue(d.detail.contains("emextras"), d.detail)
        assertTrue(d.detail.contains("ItemTierInstaller"), d.detail)
    }

    @Test
    fun `missing refmap in the owning jar is reported as a broken build`() {
        val root = Files.createTempDirectory("chrefmap").toFile()
        val mods = File(root, "mods").apply { mkdirs() }
        TestJars.jar(File(mods, "emx-all.jar"), mapOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nMixinConfigs: emextras.mixins.json\n",
            "META-INF/mods.toml" to "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"x\"\n[[mods]]\nmodId=\"emextras\"\nversion=\"1.5.1\"\ndisplayName=\"Evolved Mekanism Extras\"\n",
            "emextras.mixins.json" to "{\"package\":\"io.github.masyumero.emextras.mixin\",\"refmap\":\"emextras.refmap.json\",\"mixins\":[]}",
            "io/github/masyumero/emextras/EMExtras.class" to "x",
        ))
        TestJars.forgeMod(mods, "aae.jar", "advanced_ae")
        val index = ModIndex.load(mods)
        assertEquals(setOf("emextras.mixins.json"), index.byModId("emextras")!!.missingRefmaps)
        val d = service.diagnoseText(crash, null, index)
        assertEquals(Cause.MIXIN_NO_REFMAP, d.cause, d.detail)
        assertEquals("emx-all.jar", d.findings.first().jar)
        assertEquals("emx-all.jar", d.topSuspect?.jar)
    }

    @Test
    fun `refmap warning in the log upgrades only the failing config`() {
        val log = """
            [main/WARN] [mixin/]: Reference map 'emextras.refmap.json' for emextras.mixins.json could not be read. If this is a development environment you can ignore this message
            [main/WARN] [mixin/]: Reference map 'other.refmap.json' for other.mixins.json could not be read. If this is a development environment you can ignore this message
            org.spongepowered.asm.mixin.throwables.MixinApplyError: Mixin [emextras.mixins.json:MixinItemAlloy] from phase [DEFAULT] in config [emextras.mixins.json] FAILED during APPLY
        """.trimIndent()
        val d = service.diagnoseText(log)
        assertEquals(Cause.MIXIN_NO_REFMAP, d.cause, d.detail)
        assertTrue(d.findings.none { it.config == "other.mixins.json" })
    }
}

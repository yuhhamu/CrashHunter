package dev.crashhunter.core

import dev.crashhunter.core.analyze.CrashFingerprint
import dev.crashhunter.core.bisect.TrialOutcome
import dev.crashhunter.core.bisect.TrialResult
import dev.crashhunter.core.fix.RemoteFile
import dev.crashhunter.core.fix.RemoteSource
import dev.crashhunter.core.fix.VersionEntry
import dev.crashhunter.core.tune.ModPolicy
import dev.crashhunter.core.tune.ModRule
import dev.crashhunter.core.tune.Removed
import dev.crashhunter.core.tune.ReplaceDone
import dev.crashhunter.core.tune.Replaced
import dev.crashhunter.core.tune.TuneEngine
import dev.crashhunter.core.tune.TuneFind
import dev.crashhunter.core.tune.TuneStatus
import dev.crashhunter.core.tune.inRange
import dev.crashhunter.core.tune.closestFirst
import dev.crashhunter.core.tune.LoaderOps
import dev.crashhunter.core.tune.LoaderChanged
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuneEngineTest {
    private val fp = CrashFingerprint("boom")

    private class Pack(
        mods: List<String>,
        val conflicts: List<Set<String>>,
        val alts: Map<String, List<String>> = emptyMap(),
        val fixedBy: Set<String> = emptySet(),
        val requires: Map<String, String> = emptyMap(),
    ) {
        val enabled = mods.toMutableSet()
        fun crash(): Set<String>? = conflicts.firstOrNull { c -> c.all { it in enabled } && c.none { it in fixedByActive() } }
        fun fixedByActive() = fixedBy.filter { it in enabled }.map { it.substringBefore("@") }.toSet()
        fun remote(name: String) = RemoteFile(RemoteSource.CURSEFORGE, name, null, null, null, "1", name, name)
    }

    private fun engine(p: Pack, policy: Map<String, ModPolicy>) = TuneEngine(
        initialPolicy = policy,
        find = { _ -> p.crash()?.let { TuneFind.Culprits(it.toList(), CrashFingerprint("boom")) } ?: TuneFind.Launches },
        versions = { f -> (p.alts[f] ?: emptyList()).map { p.remote(it) } },
        replace = { f, r -> p.enabled -= f; p.enabled += r.fileName; ReplaceDone(r.fileName) { p.enabled -= r.fileName; p.enabled += f } },
        launch = { if (p.crash() == null) TrialOutcome(TrialResult.PASS) else TrialOutcome(TrialResult.FAIL, fp) },
        dependents = { f -> setOf(f) + p.requires.filterValues { it == f }.keys.filter { it in p.enabled } },
        disable = { fs -> p.enabled -= fs },
        sameFailure = { a, b -> a == b },
    )

    @Test
    fun `a working version is preferred over removing a mod`() = runBlocking {
        val p = Pack(listOf("a", "b", "c"), listOf(setOf("a", "b")), alts = mapOf("a" to listOf("a@2", "a@3")), fixedBy = setOf("a@3"))
        val pack = object {
            val enabled = p.enabled
        }
        val e = TuneEngine(
            initialPolicy = emptyMap(),
            find = { _ -> if ("b" in pack.enabled && pack.enabled.any { it == "a" || it == "a@2" }) TuneFind.Culprits(listOf("a", "b"), fp) else TuneFind.Launches },
            versions = { f -> if (f == "a") listOf(p.remote("a@2"), p.remote("a@3")) else emptyList() },
            replace = { f, r -> pack.enabled -= f; pack.enabled += r.fileName; ReplaceDone(r.fileName) { pack.enabled -= r.fileName; pack.enabled += f } },
            launch = { if ("b" in pack.enabled && pack.enabled.any { it == "a" || it == "a@2" }) TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS) },
            dependents = { setOf(it) },
            disable = { pack.enabled -= it },
            sameFailure = { a, b -> a == b },
        )
        val r = e.run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals(1, r.steps.size)
        val step = r.steps.single() as Replaced
        assertEquals("a@3", step.newFile)
        assertTrue("b" in pack.enabled && "a@3" in pack.enabled && "a@2" !in pack.enabled)
    }

    @Test
    fun `kept mods stay and the other side of the pair is removed`() = runBlocking {
        val p = Pack(listOf("a", "b", "c"), listOf(setOf("a", "b")))
        val r = engine(p, mapOf("a" to ModPolicy.KEEP)).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals("b", (r.steps.single() as Removed).file)
        assertTrue("a" in p.enabled && "c" in p.enabled)
    }

    @Test
    fun `the culprit with fewer dependents is removed`() = runBlocking {
        val p = Pack(listOf("a", "b", "x", "y"), listOf(setOf("a", "b")), requires = mapOf("x" to "a", "y" to "a"))
        val r = engine(p, emptyMap()).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals("b", (r.steps.single() as Removed).file)
        assertTrue(setOf("a", "x", "y").all { it in p.enabled })
    }

    @Test
    fun `removing a mod takes its dependents unless one of them must stay`() = runBlocking {
        val p = Pack(listOf("a", "b", "x"), listOf(setOf("a", "b")), requires = mapOf("x" to "b"))
        val r = engine(p, mapOf("x" to ModPolicy.KEEP)).run()
        assertEquals("a", (r.steps.single() as Removed).file)
        assertTrue("x" in p.enabled && "b" in p.enabled)
    }

    @Test
    fun `only kept mods in the culprit set means blocked`() = runBlocking {
        val p = Pack(listOf("a", "b", "c"), listOf(setOf("a", "b")))
        val r = engine(p, mapOf("a" to ModPolicy.KEEP, "b" to ModPolicy.PIN)).run()
        assertEquals(TuneStatus.BLOCKED, r.status)
        assertEquals(setOf("a", "b"), r.blockedBy.toSet())
    }

    @Test
    fun `several problems are fixed one after another`() = runBlocking {
        val p = Pack(listOf("a", "b", "c", "d", "e"), listOf(setOf("a", "b"), setOf("d")))
        val r = engine(p, mapOf("a" to ModPolicy.KEEP)).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals(listOf("b", "d"), r.steps.map { (it as Removed).file })
    }

    @Test
    fun `allowed versions filter the list`() {
        val entries = (5 downTo 1).map { VersionEntry(RemoteFile(RemoteSource.CURSEFORGE, "m-$it.jar", null, null, null, "1", "$it", "1.$it"), "2026-0$it", it == 3) }
        assertEquals(listOf("4", "2"), inRange(entries, ModRule(ModPolicy.KEEP, allowed = listOf("2", "4"))).map { it.remote.fileId })
        assertEquals(5, inRange(entries, ModRule(ModPolicy.KEEP)).size)
    }

    @Test
    fun `loader versions are tried closest to the current one first`() {
        val all = listOf("47.4.9", "47.4.8", "47.4.6", "47.4.4", "47.3.0")
        assertEquals(listOf("47.4.8", "47.4.4", "47.4.9", "47.3.0"), closestFirst(all, "47.4.6", null))
        assertEquals(listOf("47.4.4", "47.3.0"), closestFirst(all, "47.4.6", listOf("47.4.4", "47.3.0", "47.4.6")))
    }

    @Test
    fun `a forge version change can fix a crash that is not caused by mods`() = runBlocking {
        var forge = "47.4.6"
        val e = TuneEngine(
            initialPolicy = emptyMap(),
            find = { _ -> if (forge == "47.4.6") TuneFind.Stop(TuneStatus.NOT_A_MOD) else TuneFind.Launches },
            versions = { emptyList() },
            replace = { _, _ -> null },
            launch = { if (forge == "47.4.6") TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS) },
            dependents = { setOf(it) },
            disable = {},
            sameFailure = { a, b -> a == b },
            loader = LoaderOps("net.minecraftforge", "47.4.6", ModPolicy.KEEP, { listOf("47.4.8") }, { v -> val old = forge; forge = v; ReplaceDone(v) { forge = old } }),
        )
        val r = e.run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals("47.4.8", (r.steps.single() as LoaderChanged).to)
    }

    @Test
    fun `a locked forge is never changed`() = runBlocking {
        var changed = false
        val e = TuneEngine(
            initialPolicy = emptyMap(),
            find = { _ -> TuneFind.Stop(TuneStatus.NOT_A_MOD) },
            versions = { emptyList() }, replace = { _, _ -> null }, launch = { TrialOutcome(TrialResult.FAIL, fp) },
            dependents = { setOf(it) }, disable = {}, sameFailure = { a, b -> a == b },
            loader = LoaderOps("net.minecraftforge", "47.4.6", ModPolicy.PIN, { listOf("47.4.8") }, { v -> changed = true; ReplaceDone(v) {} }),
        )
        assertEquals(TuneStatus.NOT_A_MOD, e.run().status)
        assertTrue(!changed)
    }

    @Test
    fun `a broken mod file that is locked stops at once`() = runBlocking {
        var finds = 0
        val e = TuneEngine(
            initialPolicy = mapOf("a" to ModPolicy.PIN),
            find = { _ -> finds++; TuneFind.Culprits(listOf("a"), fp, direct = true) },
            versions = { listOf(RemoteFile(RemoteSource.CURSEFORGE, "a2", null, null, null, "1", "2", "2")) },
            replace = { _, _ -> error("must not replace") },
            launch = { error("must not launch") },
            dependents = { setOf(it) }, disable = { error("must not remove") }, sameFailure = { a, b -> a == b },
        )
        val r = e.run()
        assertEquals(TuneStatus.BLOCKED, r.status)
        assertEquals(1, finds)
    }

    @Test
    fun `a broken mod file is swapped for another version without a search`() = runBlocking {
        var current = "a"
        val e = TuneEngine(
            initialPolicy = emptyMap(),
            find = { _ -> if (current == "a") TuneFind.Culprits(listOf("a"), fp, direct = true) else TuneFind.Launches },
            versions = { listOf(RemoteFile(RemoteSource.CURSEFORGE, "a2", null, null, null, "1", "2", "2")) },
            replace = { _, r -> current = r.fileName; ReplaceDone(r.fileName) { current = "a" } },
            launch = { if (current == "a") TrialOutcome(TrialResult.FAIL, fp) else TrialOutcome(TrialResult.PASS) },
            dependents = { setOf(it) }, disable = {}, sameFailure = { a, b -> a == b },
        )
        val r = e.run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals("a2", (r.steps.single() as Replaced).newFile)
    }

    @Test
    fun `fixes made before the search are kept as steps`() = runBlocking {
        val fix = dev.crashhunter.core.tune.Fixed(1, dev.crashhunter.core.fix.FixKind.ENABLE_PROVIDER, "core", mapOf("mod" to "core"),
            listOf(dev.crashhunter.core.fix.EnableMod("core.jar.disabled")), true)
        val e = TuneEngine(
            initialPolicy = emptyMap(),
            find = { _ -> TuneFind.WithFixes(listOf(fix), TuneFind.Launches) },
            versions = { emptyList() }, replace = { _, _ -> null }, launch = { TrialOutcome(TrialResult.PASS) },
            dependents = { setOf(it) }, disable = {}, sameFailure = { a, b -> a == b },
        )
        val r = e.run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals(listOf<dev.crashhunter.core.tune.TuneStep>(fix), r.steps)
        val props = dev.crashhunter.core.tune.TuneSession.proposals(r, emptyMap())
        assertEquals(dev.crashhunter.core.fix.FixKind.ENABLE_PROVIDER, props.single().kind)
    }

    private fun removeFirst(p: Pack, policy: Map<String, ModPolicy> = emptyMap()) = TuneEngine(
        initialPolicy = policy,
        find = { _ -> p.crash()?.let { TuneFind.Culprits(it.toList(), CrashFingerprint("boom")) } ?: TuneFind.Launches },
        versions = { f -> (p.alts[f] ?: emptyList()).map { p.remote(it) } },
        replace = { f, r -> p.enabled -= f; p.enabled += r.fileName; ReplaceDone(r.fileName) { p.enabled -= r.fileName; p.enabled += f } },
        launch = { if (p.crash() == null) TrialOutcome(TrialResult.PASS) else TrialOutcome(TrialResult.FAIL, fp) },
        dependents = { f -> setOf(f) + p.requires.filterValues { it == f }.keys.filter { it in p.enabled } },
        disable = { fs -> p.enabled -= fs },
        enable = { fs -> p.enabled += fs },
        sameFailure = { a, b -> a == b },
        removeFirst = true,
    )

    @Test
    fun `remove first, then bring the mod back with a working version`() = runBlocking {
        val p = Pack(listOf("a", "b", "c"), listOf(setOf("a", "b"), setOf("a@2", "b")), alts = mapOf("a" to listOf("a@2", "a@3")))
        val r = removeFirst(p).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals(setOf("a@3", "b", "c"), p.enabled)
        assertTrue(r.steps.none { it is Removed }, r.steps.toString())
        assertEquals("a@3", (r.steps.single { it is Replaced } as Replaced).newFile)
    }

    @Test
    fun `a mod that cannot come back stays removed`() = runBlocking {
        val p = Pack(listOf("a", "b", "c"), listOf(setOf("a", "b"), setOf("a@2", "b")), alts = mapOf("a" to listOf("a@2")))
        val r = removeFirst(p).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals(setOf("b", "c"), p.enabled)
        assertEquals("a", (r.steps.single { it is Removed } as Removed).file)
    }

    @Test
    fun `the other side of a pair can be changed to bring a mod back with its dependents`() = runBlocking {
        val p = Pack(listOf("a", "b", "d", "e", "f"), listOf(setOf("a", "b")), alts = mapOf("b" to listOf("b@2")),
            requires = mapOf("d" to "a", "e" to "b", "f" to "b"))
        val r = removeFirst(p).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals(setOf("a", "b@2", "d", "e", "f"), p.enabled)
        assertTrue(r.steps.none { it is Removed }, r.steps.toString())
    }

    @Test
    fun `kept mods still try versions before anything else`() = runBlocking {
        val p = Pack(listOf("a", "b"), listOf(setOf("a", "b")), alts = mapOf("a" to listOf("a@2")))
        val r = removeFirst(p, mapOf("a" to ModPolicy.KEEP, "b" to ModPolicy.KEEP)).run()
        assertEquals(TuneStatus.LAUNCHED, r.status)
        assertEquals(setOf("a@2", "b"), p.enabled)
    }
}

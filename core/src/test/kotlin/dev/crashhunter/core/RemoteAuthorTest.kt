package dev.crashhunter.core

import dev.crashhunter.core.fix.remote.CfMod
import dev.crashhunter.core.fix.remote.ModrinthClient
import dev.crashhunter.core.fix.remote.MrFile
import dev.crashhunter.core.fix.remote.MrMember
import dev.crashhunter.core.fix.remote.MrVersion
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteAuthorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `modrinth candidates carry project name and author`() {
        val v = MrVersion("v1", "p1", "1.0", files = listOf(MrFile("https://cdn.modrinth.com/a.jar", "a.jar", true)))
        val r = ModrinthClient.toRemote(v, "geckolib", "GeckoLib", "Gecko")!!
        assertEquals("GeckoLib", r.projectName)
        assertEquals("Gecko", r.author)
        val members = json.decodeFromString(ListSerializer(MrMember.serializer()), """[{"role":"Member","user":{"username":"b"}},{"role":"Owner","user":{"username":"a"}}]""")
        assertEquals("a", (members.firstOrNull { it.role == "Owner" } ?: members.first()).user.username)
    }

    @Test
    fun `curseforge mod authors are read`() {
        val m = json.decodeFromString(CfMod.serializer(), """{"id":1,"name":"JEI","authors":[{"id":5,"name":"mezz","url":"x"}]}""")
        assertEquals("mezz", m.authors.first().name)
    }
}

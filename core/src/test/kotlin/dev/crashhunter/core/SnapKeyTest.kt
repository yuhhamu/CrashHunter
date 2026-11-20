package dev.crashhunter.core

import dev.crashhunter.core.db.SNAP_KEYS
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapKeyTest {
    @Test
    fun `embedded snapshot key matches the server public key file`() {
        val pub = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "server/snapshot-key.pub") }.first { it.isFile }
        assertEquals(pub.readText().trim(), SNAP_KEYS.single())
    }
}

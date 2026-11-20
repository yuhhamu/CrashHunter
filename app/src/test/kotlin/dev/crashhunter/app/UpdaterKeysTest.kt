package dev.crashhunter.app

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdaterKeysTest {
    @Test
    fun bothKeysAreTrusted() {
        assertEquals(2, Updater.KEYS.distinct().size)
        Updater.KEYS.forEach { k ->
            java.security.KeyFactory.getInstance("Ed25519").generatePublic(java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(k)))
        }
    }
}

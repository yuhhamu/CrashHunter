package dev.crashhunter.core.util

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Ed25519 {
    fun verify(data: ByteArray, signature: String, publicKey: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())))
        Signature.getInstance("Ed25519").run { initVerify(key); update(data); verify(Base64.getDecoder().decode(signature.trim())) }
    }.getOrDefault(false)
}

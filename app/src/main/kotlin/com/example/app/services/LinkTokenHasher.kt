package com.example.app.services

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LinkTokenHasher(
    private val secret: String
) {
    private val secureRandom = SecureRandom()

    fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    fun hash(token: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(key)
        val digest = mac.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        return digest.toHex()
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte) }

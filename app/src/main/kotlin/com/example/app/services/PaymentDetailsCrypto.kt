package com.example.app.services

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PaymentDetailsCrypto(
    keyBytes: ByteArray
) {
    private val key = SecretKeySpec(keyBytes, "AES")
    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = iv + ciphertext
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(payload: String): String {
        val decoded = Base64.getDecoder().decode(payload)
        require(decoded.size > 12) { "payload too short" }
        val iv = decoded.copyOfRange(0, 12)
        val ciphertext = decoded.copyOfRange(12, decoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return plaintext.toString(Charsets.UTF_8)
    }
}

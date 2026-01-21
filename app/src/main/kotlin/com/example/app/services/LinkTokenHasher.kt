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
    private val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
    private val secretKeySpec = SecretKeySpec(secretBytes, HMAC_ALGORITHM)

    fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    fun hash(token: String): String {
        return encodeHex(hmac(token))
    }

    fun hashLegacy(token: String): String {
        return encodeHexLegacy(hmac(token))
    }

    fun hashesForLookup(token: String): List<String> {
        val digest = hmac(token)
        val canonical = encodeHex(digest)
        val legacy = encodeHexLegacy(digest)
        return if (canonical == legacy) {
            listOf(canonical)
        } else {
            listOf(canonical, legacy)
        }
    }

    private fun hmac(token: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(secretKeySpec)
        return mac.doFinal(token.toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

private val HEX_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    'a', 'b', 'c', 'd', 'e', 'f'
)

private fun encodeHex(bytes: ByteArray): String {
    val result = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val int = byte.toInt() and 0xff
        val offset = index * 2
        result[offset] = HEX_CHARS[int ushr 4]
        result[offset + 1] = HEX_CHARS[int and 0x0f]
    }
    return String(result)
}

private fun encodeHexLegacy(bytes: ByteArray): String =
    bytes.joinToString("") { byte -> "%02x".format(byte) }

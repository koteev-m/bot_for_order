package com.example.app.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class VerifiedInitData(
    val userId: Long,
    val authDate: Instant,
    val raw: Map<String, String>
)

object TelegramInitDataVerifier {
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    /**
     * @param initData raw initData string from Telegram.WebApp.initData (as is)
     * @param botToken token of the bot owning the Mini App (Shop bot)
     * @param maxAgeSeconds optional TTL (e.g. 86400 = 24h). If <=0, TTL is not checked
     */
    fun verify(
        initData: String,
        botToken: String,
        maxAgeSeconds: Long = 86400,
        clock: Clock = Clock.systemUTC()
    ): VerifiedInitData {
        require(initData.isNotBlank()) { "initData is empty" }

        // Decode percent-encoding but keep '+'
        val decoded = urlDecodePreservingPlus(initData)

        val pairs = decoded.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()

        val hash = pairs["hash"] ?: error("hash is missing")
        // Build data_check_string: all fields except hash and signature
        val dataFields = pairs.filterKeys { it != "hash" && it != "signature" }
        val dataCheckString = dataFields.toSortedMap()
            .entries.joinToString("\n") { (k, v) -> "$k=$v" }

        val secretKey = hmacSha256(
            "WebAppData".toByteArray(StandardCharsets.UTF_8),
            botToken.toByteArray(StandardCharsets.UTF_8)
        )
        val calc = hmacSha256(
            secretKey,
            dataCheckString.toByteArray(StandardCharsets.UTF_8)
        ).toHexLower()

        if (!constantTimeEquals(calc, hash)) error("invalid hash")

        val authSec = pairs["auth_date"]?.toLongOrNull() ?: error("auth_date missing")
        if (maxAgeSeconds > 0) {
            val now = clock.instant().epochSecond
            if (authSec <= 0L || now - authSec > maxAgeSeconds) error("initData expired")
        }

        val userJson = pairs["user"] ?: error("user missing")
        val userId = parseUserIdFromJson(userJson)

        return VerifiedInitData(
            userId = userId,
            authDate = Instant.ofEpochSecond(authSec),
            raw = pairs
        )
    }

    private fun parseUserIdFromJson(userJson: String): Long {
        val obj = json.parseToJsonElement(userJson) as? JsonObject ?: error("user not json")
        val idValue = obj["id"]?.jsonPrimitive?.longOrNull
        return idValue ?: error("user.id missing")
    }

    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) {
            r = r or (a[i].code xor b[i].code)
        }
        return r == 0
    }

    private fun urlDecodePreservingPlus(s: String): String {
        // URLDecoder.decode would turn '+' into ' ' — prevent this.
        val fixed = s.replace("+", "%2B")
        return URLDecoder.decode(fixed, StandardCharsets.UTF_8)
    }
}

package com.example.app.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TelegramInitDataVerifierTest : StringSpec({
    "verify accepts valid initData" {
        val initData = initDataVector()
        val clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_200 + 30), ZoneOffset.UTC)
        val verifier = TelegramInitDataVerifier("test:token", 86_400, clock)

        val verified = verifier.verify(initData)

        verified.userId shouldBe 42L
        verified.authDate shouldBe Instant.ofEpochSecond(1_704_067_200)
    }

    "verify rejects invalid hash" {
        val initData = initDataVector().replace(
            "bc3cfc0c4af26fe066f7ba535aed63df7d307410fa12e35ad4dd31482f8f8c28",
            "deadbeef"
        )
        val clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_200 + 30), ZoneOffset.UTC)
        val verifier = TelegramInitDataVerifier("test:token", 86_400, clock)

        shouldThrow<IllegalStateException> {
            verifier.verify(initData)
        }
    }

    "verify rejects expired auth_date" {
        val initData = initDataVector()
        val clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_200 + 86_401), ZoneOffset.UTC)
        val verifier = TelegramInitDataVerifier("test:token", 86_400, clock)

        shouldThrow<IllegalStateException> {
            verifier.verify(initData)
        }
    }

    "verify accepts initData with signature field" {
        val clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_200 + 30), ZoneOffset.UTC)
        val verifier = TelegramInitDataVerifier("test:token", 86_400, clock)

        val initData = initDataWithSignature()
        val verified = verifier.verify(initData)

        verified.userId shouldBe 7L
        verified.authDate shouldBe Instant.ofEpochSecond(1_704_067_200)
    }
})

private fun initDataVector(): String =
    "auth_date=1704067200&query_id=AAE-1&user=%7B%22id%22%3A42%2C%22first_name%22%3A%22Alice%22%7D" +
        "&hash=bc3cfc0c4af26fe066f7ba535aed63df7d307410fa12e35ad4dd31482f8f8c28"

private fun initDataWithSignature(): String {
    val botToken = "test:token"
    val authDate = "1704067200"
    val queryId = "AAE-2"
    val userJson = """{"id":7,"first_name":"Bob"}"""
    val signature = "any-signature"

    val dataCheckString = mapOf(
        "auth_date" to authDate,
        "query_id" to queryId,
        "signature" to signature,
        "user" to userJson
    ).toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }

    val secretKey = hmacSha256(
        "WebAppData".toByteArray(StandardCharsets.UTF_8),
        botToken.toByteArray(StandardCharsets.UTF_8)
    )
    val hash = hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8)).toHexLower()

    val encodedUser = URLEncoder.encode(userJson, StandardCharsets.UTF_8)
    val encodedSignature = URLEncoder.encode(signature, StandardCharsets.UTF_8)
    return listOf(
        "auth_date=$authDate",
        "query_id=$queryId",
        "user=$encodedUser",
        "signature=$encodedSignature",
        "hash=$hash"
    ).joinToString("&")
}

private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg)
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

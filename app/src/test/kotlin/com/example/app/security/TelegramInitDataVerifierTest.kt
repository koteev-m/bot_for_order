package com.example.app.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
})

private fun initDataVector(): String =
    "auth_date=1704067200&query_id=AAE-1&user=%7B%22id%22%3A42%2C%22first_name%22%3A%22Alice%22%7D" +
        "&hash=bc3cfc0c4af26fe066f7ba535aed63df7d307410fa12e35ad4dd31482f8f8c28"

package com.example.app.services

import com.example.app.testutil.InMemoryIdempotencyRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class IdempotencyServiceTest : StringSpec({
    "expired idempotency key can be reused after ttl" {
        val repository = InMemoryIdempotencyRepository()
        val clock = TestClock(Instant.parse("2024-01-01T00:00:00Z"))
        val service = IdempotencyService(repository, clock, Duration.ofSeconds(1))
        var executions = 0

        val first = service.execute(
            merchantId = "m-1",
            userId = 10L,
            scope = "test",
            key = "key-1",
            requestHash = "hash-1"
        ) {
            executions += 1
            IdempotencyService.IdempotentResponse(
                status = HttpStatusCode.OK,
                response = "ok",
                responseJson = "\"ok\""
            )
        }

        executions shouldBe 1
        first.shouldBeInstanceOf<IdempotencyService.IdempotentOutcome.Executed<String>>()

        clock.advanceBy(Duration.ofSeconds(2))

        val second = service.execute(
            merchantId = "m-1",
            userId = 10L,
            scope = "test",
            key = "key-1",
            requestHash = "hash-1"
        ) {
            executions += 1
            IdempotencyService.IdempotentResponse(
                status = HttpStatusCode.OK,
                response = "ok",
                responseJson = "\"ok\""
            )
        }

        executions shouldBe 2
        second.shouldBeInstanceOf<IdempotencyService.IdempotentOutcome.Executed<String>>()
    }
})

private class TestClock(initial: Instant) : Clock() {
    private var now: Instant = initial

    fun advanceBy(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun withZone(zone: ZoneId): Clock = this

    override fun getZone(): ZoneId = ZoneOffset.UTC
}

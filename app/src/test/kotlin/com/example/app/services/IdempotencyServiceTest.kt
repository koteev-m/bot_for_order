package com.example.app.services

import com.example.app.testutil.InMemoryIdempotencyRepository
import com.example.app.api.ApiError
import com.example.db.IdempotencyRepository
import com.example.domain.IdempotencyKeyRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    "concurrent idempotency requests only execute once" {
        val insertedSignal = CompletableDeferred<Unit>()
        val proceedSignal = CompletableDeferred<Unit>()
        val repository = BlockingIdempotencyRepository(insertedSignal, proceedSignal)
        val clock = TestClock(Instant.parse("2024-01-01T00:00:00Z"))
        val service = IdempotencyService(repository, clock, Duration.ofMinutes(5))
        var executions = 0

        val first = async {
            service.execute(
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
        }

        insertedSignal.await()

        val second = async {
            shouldThrow<ApiError> {
                service.execute(
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
            }
        }

        val error = second.await()
        error.status shouldBe HttpStatusCode.Conflict
        error.message shouldBe "idempotency_in_progress"

        proceedSignal.complete(Unit)

        val outcome = first.await()
        outcome.shouldBeInstanceOf<IdempotencyService.IdempotentOutcome.Executed<String>>()
        executions shouldBe 1
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

private class BlockingIdempotencyRepository(
    private val insertedSignal: CompletableDeferred<Unit>,
    private val proceedSignal: CompletableDeferred<Unit>
) : IdempotencyRepository {
    private val storage = ConcurrentHashMap<String, IdempotencyKeyRecord>()
    private val hasBlocked = AtomicBoolean(false)

    override suspend fun findValid(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): IdempotencyKeyRecord? {
        val record = storage[recordKey(merchantId, userId, scope, key)] ?: return null
        return if (record.createdAt.isBefore(validAfter)) null else record
    }

    override suspend fun tryInsert(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        requestHash: String,
        createdAt: Instant
    ): Boolean {
        val mapKey = recordKey(merchantId, userId, scope, key)
        val record = IdempotencyKeyRecord(
            merchantId = merchantId,
            userId = userId,
            scope = scope,
            key = key,
            requestHash = requestHash,
            responseStatus = null,
            responseJson = null,
            createdAt = createdAt
        )
        val inserted = storage.putIfAbsent(mapKey, record) == null
        if (inserted && hasBlocked.compareAndSet(false, true)) {
            insertedSignal.complete(Unit)
            proceedSignal.await()
        }
        return inserted
    }

    override suspend fun updateResponse(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        responseStatus: Int,
        responseJson: String
    ) {
        val mapKey = recordKey(merchantId, userId, scope, key)
        val existing = storage[mapKey] ?: return
        storage[mapKey] = existing.copy(responseStatus = responseStatus, responseJson = responseJson)
    }

    override suspend fun delete(merchantId: String, userId: Long, scope: String, key: String) {
        storage.remove(recordKey(merchantId, userId, scope, key))
    }

    override suspend fun deleteIfExpired(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): Boolean {
        val mapKey = recordKey(merchantId, userId, scope, key)
        val record = storage[mapKey] ?: return false
        return if (record.createdAt.isBefore(validAfter)) {
            storage.remove(mapKey) != null
        } else {
            false
        }
    }

    private fun recordKey(merchantId: String, userId: Long, scope: String, key: String): String {
        return "$merchantId:$userId:$scope:$key"
    }
}

package com.example.app.services

import com.example.app.baseTestConfig
import com.example.app.config.OutboxConfig
import com.example.db.OutboxRepository
import com.example.domain.OutboxMessage
import com.example.domain.OutboxMessageStatus
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.ktor.server.application.Application
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class OutboxWorkerTest {

    @Test
    fun `stale finalize is skipped after reclaim and current attempt can finalize`(): Unit = runBlocking {
        val clock = OutboxTestClock(Instant.parse("2024-01-01T00:00:00Z"))
        val repository = FakeOutboxRepository()
        val id = repository.insert("noop.test", "{\"stale\":true}", clock.instant())
        repository.forceState(
            id = id,
            status = OutboxMessageStatus.PROCESSING,
            attempts = 1,
            nextAttemptAt = clock.instant().minusSeconds(1)
        )

        val reclaimed = repository.fetchDueBatch(
            limit = 1,
            now = clock.instant(),
            processingLeaseUntil = clock.instant().plusSeconds(60)
        ).single()

        reclaimed.attempts shouldBeGreaterThan 1
        repository.reschedule(
            id = id,
            expectedAttempts = reclaimed.attempts - 1,
            nextAttemptAt = clock.instant().plusSeconds(120),
            lastError = "stale"
        ) shouldBe false
        repository.message(id).status shouldBe OutboxMessageStatus.PROCESSING
        repository.message(id).attempts shouldBe reclaimed.attempts

        repository.markDone(id = id, expectedAttempts = reclaimed.attempts) shouldBe true
        repository.message(id).status shouldBe OutboxMessageStatus.DONE
    }

    @Test
    fun `worker reclaims stale processing message with expired lease`(): Unit = runBlocking {
        val clock = OutboxTestClock(Instant.parse("2024-01-01T00:00:00Z"))
        val repository = FakeOutboxRepository()
        val id = repository.insert("noop.test", "{\"stale\":true}", clock.instant())
        repository.forceState(
            id = id,
            status = OutboxMessageStatus.PROCESSING,
            attempts = 1,
            nextAttemptAt = clock.instant().minusSeconds(1)
        )
        val handledPayloads = mutableListOf<String>()
        val worker = OutboxWorker(
            application = mockk<Application>(relaxed = true),
            outboxRepository = repository,
            handlerRegistry = OutboxHandlerRegistry(mapOf("noop.test" to OutboxHandler { handledPayloads += it })),
            config = baseTestConfig().copy(
                outbox = OutboxConfig(
                    enabled = true,
                    pollIntervalMs = 10,
                    batchSize = 10,
                    maxAttempts = 5,
                    baseBackoffMs = 100,
                    maxBackoffMs = 1000,
                    processingTtlMs = 600_000
                )
            ),
            clock = clock,
            random = Random(1)
        )

        worker.runOnce()

        handledPayloads shouldContainExactly listOf("{\"stale\":true}")
        repository.message(id).status shouldBe OutboxMessageStatus.DONE
    }

    @Test
    fun `worker fetches due message, invokes handler and marks done`(): Unit = runBlocking {
        val clock = OutboxTestClock(Instant.parse("2024-01-01T00:00:00Z"))
        val repository = FakeOutboxRepository()
        val id = repository.insert("noop.test", "{\"x\":1}", clock.instant())
        val handledPayloads = mutableListOf<String>()
        val worker = OutboxWorker(
            application = mockk<Application>(relaxed = true),
            outboxRepository = repository,
            handlerRegistry = OutboxHandlerRegistry(mapOf("noop.test" to OutboxHandler { handledPayloads += it })),
            config = baseTestConfig().copy(
                outbox = OutboxConfig(
                    enabled = true,
                    pollIntervalMs = 10,
                    batchSize = 10,
                    maxAttempts = 3,
                    baseBackoffMs = 100,
                    maxBackoffMs = 1000,
                    processingTtlMs = 600_000
                )
            ),
            clock = clock,
            random = Random(1)
        )

        worker.runOnce()

        handledPayloads shouldContainExactly listOf("{\"x\":1}")
        repository.message(id).status shouldBe OutboxMessageStatus.DONE
    }

    @Test
    fun `worker retries with backoff then succeeds`(): Unit = runBlocking {
        val clock = OutboxTestClock(Instant.parse("2024-01-01T00:00:00Z"))
        val repository = FakeOutboxRepository()
        val id = repository.insert("retry.test", "{}", clock.instant())
        var failuresLeft = 2
        val worker = OutboxWorker(
            application = mockk<Application>(relaxed = true),
            outboxRepository = repository,
            handlerRegistry = OutboxHandlerRegistry(
                mapOf(
                    "retry.test" to OutboxHandler {
                        if (failuresLeft > 0) {
                            failuresLeft -= 1
                            error("boom")
                        }
                    }
                )
            ),
            config = baseTestConfig().copy(
                outbox = OutboxConfig(
                    enabled = true,
                    pollIntervalMs = 10,
                    batchSize = 10,
                    maxAttempts = 5,
                    baseBackoffMs = 100,
                    maxBackoffMs = 1000,
                    processingTtlMs = 600_000
                )
            ),
            clock = clock,
            random = Random(0)
        )

        worker.runOnce()
        val firstRetry = repository.message(id)
        firstRetry.status shouldBe OutboxMessageStatus.NEW

        clock.set(firstRetry.nextAttemptAt)
        worker.runOnce()
        val secondRetry = repository.message(id)
        secondRetry.status shouldBe OutboxMessageStatus.NEW

        clock.set(secondRetry.nextAttemptAt)
        worker.runOnce()
        repository.message(id).status shouldBe OutboxMessageStatus.DONE
    }
}

private class FakeOutboxRepository : OutboxRepository {
    private var nextId = 1L
    private val storage = linkedMapOf<Long, OutboxMessage>()

    override suspend fun insert(type: String, payloadJson: String, now: Instant): Long {
        val id = nextId++
        storage[id] = OutboxMessage(
            id = id,
            type = type,
            payloadJson = payloadJson,
            status = OutboxMessageStatus.NEW,
            attempts = 0,
            nextAttemptAt = now,
            createdAt = now,
            lastError = null
        )
        return id
    }

    override suspend fun fetchDueBatch(limit: Int, now: Instant, processingLeaseUntil: Instant): List<OutboxMessage> {
        val dueIds = storage.values
            .filter {
                (it.status == OutboxMessageStatus.NEW || it.status == OutboxMessageStatus.PROCESSING) &&
                    !it.nextAttemptAt.isAfter(now)
            }
            .sortedBy { it.id }
            .take(limit)
            .map { it.id }
        return dueIds.map { id ->
            val message = checkNotNull(storage[id])
            val processing = message.copy(
                status = OutboxMessageStatus.PROCESSING,
                attempts = message.attempts + 1,
                nextAttemptAt = processingLeaseUntil
            )
            storage[id] = processing
            processing
        }
    }

    override suspend fun markDone(id: Long, expectedAttempts: Int): Boolean {
        val message = checkNotNull(storage[id])
        if (message.status != OutboxMessageStatus.PROCESSING || message.attempts != expectedAttempts) {
            return false
        }
        storage[id] = message.copy(status = OutboxMessageStatus.DONE, lastError = null)
        return true
    }

    override suspend fun reschedule(id: Long, expectedAttempts: Int, nextAttemptAt: Instant, lastError: String): Boolean {
        val message = checkNotNull(storage[id])
        if (message.status != OutboxMessageStatus.PROCESSING || message.attempts != expectedAttempts) {
            return false
        }
        storage[id] = message.copy(
            status = OutboxMessageStatus.NEW,
            nextAttemptAt = nextAttemptAt,
            lastError = lastError
        )
        return true
    }

    override suspend fun markFailed(id: Long, expectedAttempts: Int, lastError: String): Boolean {
        val message = checkNotNull(storage[id])
        if (message.status != OutboxMessageStatus.PROCESSING || message.attempts != expectedAttempts) {
            return false
        }
        storage[id] = message.copy(status = OutboxMessageStatus.FAILED, lastError = lastError)
        return true
    }

    override suspend fun countBacklog(now: Instant): Long = storage.values
        .count { it.status == OutboxMessageStatus.NEW && !it.nextAttemptAt.isAfter(now) }
        .toLong()

    fun message(id: Long): OutboxMessage = checkNotNull(storage[id])

    fun forceState(id: Long, status: OutboxMessageStatus, attempts: Int, nextAttemptAt: Instant) {
        val message = checkNotNull(storage[id])
        storage[id] = message.copy(status = status, attempts = attempts, nextAttemptAt = nextAttemptAt)
    }
}

private class OutboxTestClock(private var current: Instant) : Clock() {
    override fun withZone(zone: ZoneId): Clock = this
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun instant(): Instant = current
    fun set(newNow: Instant) {
        current = newNow
    }
}

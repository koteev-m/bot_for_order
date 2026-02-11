package com.example.app.services

import com.example.app.baseTestConfig
import com.example.app.config.OutboxConfig
import com.example.bots.InstrumentedTelegramBot
import com.example.bots.TelegramClients
import com.example.db.EventLogRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrdersRepository
import com.example.db.OutboxRepository
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.OutboxMessage
import com.example.domain.OutboxMessageStatus
import com.example.domain.hold.HoldService
import com.example.domain.hold.OrderHoldService
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.ktor.server.application.Application
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class OrderStatusOutboxTest {

    @Test
    fun `change status writes buyer notification to outbox payload`(): Unit = runBlocking {
        val ordersRepository = mockk<OrdersRepository>()
        val orderLinesRepository = mockk<OrderLinesRepository>(relaxed = true)
        val orderHoldService = mockk<OrderHoldService>(relaxed = true)
        val holdService = mockk<HoldService>(relaxed = true)
        val eventLogRepository = mockk<EventLogRepository>(relaxed = true)
        val buyerOutbox = mockk<BuyerStatusNotificationOutbox>()
        val clock = Clock.fixed(Instant.parse("2025-01-01T10:00:00Z"), ZoneOffset.UTC)
        val order = Order(
            id = "order-1",
            merchantId = "m-1",
            userId = 10L,
            itemId = "item-1",
            variantId = null,
            qty = 1,
            currency = "RUB",
            amountMinor = 1000,
            deliveryOption = "pickup",
            addressJson = null,
            provider = null,
            providerChargeId = null,
            telegramPaymentChargeId = null,
            invoiceMessageId = null,
            status = OrderStatus.paid,
            createdAt = Instant.parse("2025-01-01T09:00:00Z"),
            updatedAt = Instant.parse("2025-01-01T09:00:00Z")
        )

        coEvery { ordersRepository.get("order-1") } returnsMany listOf(order, order.copy(status = OrderStatus.fulfillment))
        every { buyerOutbox.payloadJson(any()) } answers {
            Json.encodeToString(BuyerStatusNotificationPayload.serializer(), firstArg())
        }
        coEvery { ordersRepository.setStatusWithOutbox(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit

        val service = OrderStatusService(
            ordersRepository = ordersRepository,
            orderLinesRepository = orderLinesRepository,
            orderHoldService = orderHoldService,
            holdService = holdService,
            eventLogRepository = eventLogRepository,
            buyerStatusNotificationOutbox = buyerOutbox,
            clock = clock
        )

        val result = service.changeStatus("order-1", OrderStatus.fulfillment, 42L, "Передано в комплектацию")

        result.changed shouldBe true
        result.order.status shouldBe OrderStatus.fulfillment
        coVerify(exactly = 1) {
            ordersRepository.setStatusWithOutbox(
                id = "order-1",
                status = OrderStatus.fulfillment,
                actorId = 42L,
                comment = "Передано в комплектацию",
                statusChangedAt = Instant.parse("2025-01-01T10:00:00Z"),
                outboxType = BuyerStatusNotificationOutbox.BUYER_STATUS_NOTIFICATION,
                outboxPayloadJson = withArg { payloadJson ->
                    val payload = Json.decodeFromString(BuyerStatusNotificationPayload.serializer(), payloadJson)
                    payload.orderId shouldBe "order-1"
                    payload.buyerUserId shouldBe 10L
                    payload.status shouldBe OrderStatus.fulfillment
                    payload.comment shouldBe "Передано в комплектацию"
                    payload.locale shouldBe null
                },
                outboxNow = Instant.parse("2025-01-01T10:00:00Z")
            )
        }
    }

    @Test
    fun `worker handles buyer status notification and marks done`(): Unit = runBlocking {
        val clock = LocalOutboxTestClock(Instant.parse("2025-01-01T10:00:00Z"))
        val repository = LocalFakeOutboxRepository()
        val clients = mockk<TelegramClients>()
        val shopBot = mockk<InstrumentedTelegramBot>()
        every { clients.shopBot } returns shopBot
        every { shopBot.execute(any<SendMessage>()) } returns mockk<SendResponse> {
            every { isOk } returns true
        }

        val outboxHandler = BuyerStatusNotificationOutbox(
            outboxRepository = repository,
            clients = clients,
            meterRegistry = null
        )
        val payloadJson = Json.encodeToString(
            BuyerStatusNotificationPayload.serializer(),
            BuyerStatusNotificationPayload(
                orderId = "order-42",
                buyerUserId = 101L,
                status = OrderStatus.delivered,
                comment = null,
                locale = null
            )
        )
        val id = repository.insert(BuyerStatusNotificationOutbox.BUYER_STATUS_NOTIFICATION, payloadJson, clock.instant())

        val worker = OutboxWorker(
            application = mockk<Application>(relaxed = true),
            outboxRepository = repository,
            handlerRegistry = OutboxHandlerRegistry(
                mapOf(BuyerStatusNotificationOutbox.BUYER_STATUS_NOTIFICATION to OutboxHandler { outboxHandler.handle(it) })
            ),
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

        repository.message(id).status shouldBe OutboxMessageStatus.DONE
        verify(exactly = 1) { shopBot.execute(any<SendMessage>()) }
    }
}

private class LocalFakeOutboxRepository : OutboxRepository {
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
        storage[id] = message.copy(status = OutboxMessageStatus.NEW, nextAttemptAt = nextAttemptAt, lastError = lastError)
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

    override suspend fun countBacklog(now: Instant): Long {
        return storage.values.count { it.status == OutboxMessageStatus.NEW && !it.nextAttemptAt.isAfter(now) }.toLong()
    }

    fun message(id: Long): OutboxMessage = checkNotNull(storage[id])
}

private class LocalOutboxTestClock(private var current: Instant) : Clock() {
    override fun withZone(zone: ZoneId): Clock = this
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun instant(): Instant = current
}

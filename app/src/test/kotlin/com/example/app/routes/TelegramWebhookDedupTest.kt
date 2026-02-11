package com.example.app.routes

import com.example.app.baseTestConfig
import com.example.app.services.InventoryService
import com.example.app.services.ItemsService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.MediaStateStore
import com.example.app.services.OffersService
import com.example.app.services.OrderStatusService
import com.example.app.services.PaymentDetailsStateStore
import com.example.app.services.PaymentRejectReasonStateStore
import com.example.app.services.PostService
import com.example.app.testutil.InMemoryTelegramWebhookDedupRepository
import com.example.bots.InstrumentedTelegramBot
import com.example.bots.TelegramClients
import com.example.db.AdminUsersRepository
import com.example.db.ItemMediaRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrdersRepository
import com.example.db.TelegramWebhookDedupRepository
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class TelegramWebhookDedupTest : StringSpec({
    "acquire uses two-phase state and skips duplicate processed updates" {
        val repository = InMemoryTelegramWebhookDedupRepository()
        val now = Instant.parse("2026-01-01T00:00:00Z")

        acquireTelegramUpdateProcessing(
            dedupRepository = repository,
            botType = TELEGRAM_BOT_TYPE_ADMIN,
            updateId = 42L,
            logger = mockk(relaxed = true),
            now = now
        ) shouldBe TelegramWebhookDedupDecision.ACQUIRED

        repository.markProcessed(TELEGRAM_BOT_TYPE_ADMIN, 42L, now.plusSeconds(1))

        acquireTelegramUpdateProcessing(
            dedupRepository = repository,
            botType = TELEGRAM_BOT_TYPE_ADMIN,
            updateId = 42L,
            logger = mockk(relaxed = true),
            now = now.plusSeconds(2)
        ) shouldBe TelegramWebhookDedupDecision.ALREADY_PROCESSED
    }

    "admin webhook returns 5xx on processing failure and retries same update successfully" {
        val cfg = baseTestConfig()
        val dedupRepository = InMemoryTelegramWebhookDedupRepository()
        val adminBot = mockk<InstrumentedTelegramBot>()
        every { adminBot.execute(any<SendMessage>()) } throws RuntimeException("boom") andThen mockk<SendResponse>(relaxed = true)
        val clients = mockk<TelegramClients>()
        every { clients.adminBot } returns adminBot

        val payload = """
            {
              "update_id": 7002,
              "message": {
                "message_id": 1,
                "date": 1710000000,
                "text": "ping",
                "from": {"id": 123456, "first_name": "U"},
                "chat": {"id": 123456, "type": "private"}
              }
            }
        """.trimIndent()

        testApplication {
            application {
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { clients }
                            single { mockk<ItemsService>(relaxed = true) }
                            single { mockk<ItemMediaRepository>(relaxed = true) }
                            single { MediaStateStore() }
                            single { PaymentDetailsStateStore() }
                            single { PaymentRejectReasonStateStore() }
                            single { mockk<PostService>(relaxed = true) }
                            single { mockk<OrderStatusService>(relaxed = true) }
                            single { mockk<OffersService>(relaxed = true) }
                            single { mockk<InventoryService>(relaxed = true) }
                            single { mockk<ManualPaymentsService>(relaxed = true) }
                            single { mockk<OrdersRepository>(relaxed = true) }
                            single { mockk<OrderDeliveryRepository>(relaxed = true) }
                            single { mockk<com.example.db.AuditLogRepository>(relaxed = true) }
                            single { mockk<AdminUsersRepository>(relaxed = true) }
                            single<TelegramWebhookDedupRepository> { dedupRepository }
                        }
                    )
                }
                installAdminWebhook()
            }

            client.post("/tg/admin") {
                header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.adminWebhookSecret)
                setBody(payload)
            }.status shouldBe HttpStatusCode.InternalServerError

            client.post("/tg/admin") {
                header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.adminWebhookSecret)
                setBody(payload)
            }.status shouldBe HttpStatusCode.OK
        }

        verify(exactly = 2) { adminBot.execute(any<SendMessage>()) }
    }

    "admin webhook returns conflict for in-progress update and does not execute side-effects" {
        val cfg = baseTestConfig()
        val dedupRepository = InMemoryTelegramWebhookDedupRepository().apply {
            seedProcessing(
                botType = TELEGRAM_BOT_TYPE_ADMIN,
                updateId = 7004L,
                createdAt = Instant.now()
            )
        }
        val adminBot = mockk<InstrumentedTelegramBot>()
        every { adminBot.execute(any<SendMessage>()) } returns mockk<SendResponse>(relaxed = true)
        val clients = mockk<TelegramClients>()
        every { clients.adminBot } returns adminBot

        val payload = """
            {
              "update_id": 7004,
              "message": {
                "message_id": 1,
                "date": 1710000000,
                "text": "ping",
                "from": {"id": 123456, "first_name": "U"},
                "chat": {"id": 123456, "type": "private"}
              }
            }
        """.trimIndent()

        testApplication {
            application {
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { clients }
                            single { mockk<ItemsService>(relaxed = true) }
                            single { mockk<ItemMediaRepository>(relaxed = true) }
                            single { MediaStateStore() }
                            single { PaymentDetailsStateStore() }
                            single { PaymentRejectReasonStateStore() }
                            single { mockk<PostService>(relaxed = true) }
                            single { mockk<OrderStatusService>(relaxed = true) }
                            single { mockk<OffersService>(relaxed = true) }
                            single { mockk<InventoryService>(relaxed = true) }
                            single { mockk<ManualPaymentsService>(relaxed = true) }
                            single { mockk<OrdersRepository>(relaxed = true) }
                            single { mockk<OrderDeliveryRepository>(relaxed = true) }
                            single { mockk<com.example.db.AuditLogRepository>(relaxed = true) }
                            single { mockk<AdminUsersRepository>(relaxed = true) }
                            single<TelegramWebhookDedupRepository> { dedupRepository }
                        }
                    )
                }
                installAdminWebhook()
            }

            client.post("/tg/admin") {
                header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.adminWebhookSecret)
                setBody(payload)
            }.status shouldBe HttpStatusCode.Conflict
        }

        verify(exactly = 0) { adminBot.execute(any<SendMessage>()) }
    }

    "stale processing lock is reacquired and update is processed" {
        val cfg = baseTestConfig()
        val dedupRepository = InMemoryTelegramWebhookDedupRepository().apply {
            seedProcessing(
                botType = TELEGRAM_BOT_TYPE_ADMIN,
                updateId = 7003L,
                createdAt = Instant.parse("2020-01-01T00:00:00Z")
            )
        }
        val adminBot = mockk<InstrumentedTelegramBot>()
        every { adminBot.execute(any<SendMessage>()) } returns mockk<SendResponse>(relaxed = true)
        val clients = mockk<TelegramClients>()
        every { clients.adminBot } returns adminBot

        val payload = """
            {
              "update_id": 7003,
              "message": {
                "message_id": 1,
                "date": 1710000000,
                "text": "ping",
                "from": {"id": 123456, "first_name": "U"},
                "chat": {"id": 123456, "type": "private"}
              }
            }
        """.trimIndent()

        testApplication {
            application {
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { clients }
                            single { mockk<ItemsService>(relaxed = true) }
                            single { mockk<ItemMediaRepository>(relaxed = true) }
                            single { MediaStateStore() }
                            single { PaymentDetailsStateStore() }
                            single { PaymentRejectReasonStateStore() }
                            single { mockk<PostService>(relaxed = true) }
                            single { mockk<OrderStatusService>(relaxed = true) }
                            single { mockk<OffersService>(relaxed = true) }
                            single { mockk<InventoryService>(relaxed = true) }
                            single { mockk<ManualPaymentsService>(relaxed = true) }
                            single { mockk<OrdersRepository>(relaxed = true) }
                            single { mockk<OrderDeliveryRepository>(relaxed = true) }
                            single { mockk<com.example.db.AuditLogRepository>(relaxed = true) }
                            single { mockk<AdminUsersRepository>(relaxed = true) }
                            single<TelegramWebhookDedupRepository> { dedupRepository }
                        }
                    )
                }
                installAdminWebhook()
            }

            client.post("/tg/admin") {
                header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.adminWebhookSecret)
                setBody(payload)
            }.status shouldBe HttpStatusCode.OK
        }

        verify(exactly = 1) { adminBot.execute(any<SendMessage>()) }
    }


    "admin webhook returns retry-after on in-progress update" {
        val cfg = baseTestConfig()
        val dedupRepository = InMemoryTelegramWebhookDedupRepository().apply {
            seedProcessing(
                botType = TELEGRAM_BOT_TYPE_ADMIN,
                updateId = 7010L,
                createdAt = Instant.now()
            )
        }
        val adminBot = mockk<InstrumentedTelegramBot>()
        every { adminBot.execute(any<SendMessage>()) } returns mockk<SendResponse>(relaxed = true)
        val clients = mockk<TelegramClients>()
        every { clients.adminBot } returns adminBot

        val payload = """
            {
              "update_id": 7010,
              "message": {
                "message_id": 1,
                "date": 1710000000,
                "text": "ping",
                "from": {"id": 123456, "first_name": "U"},
                "chat": {"id": 123456, "type": "private"}
              }
            }
        """.trimIndent()

        testApplication {
            application {
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { clients }
                            single { mockk<ItemsService>(relaxed = true) }
                            single { mockk<ItemMediaRepository>(relaxed = true) }
                            single { MediaStateStore() }
                            single { PaymentDetailsStateStore() }
                            single { PaymentRejectReasonStateStore() }
                            single { mockk<PostService>(relaxed = true) }
                            single { mockk<OrderStatusService>(relaxed = true) }
                            single { mockk<OffersService>(relaxed = true) }
                            single { mockk<InventoryService>(relaxed = true) }
                            single { mockk<ManualPaymentsService>(relaxed = true) }
                            single { mockk<OrdersRepository>(relaxed = true) }
                            single { mockk<OrderDeliveryRepository>(relaxed = true) }
                            single { mockk<com.example.db.AuditLogRepository>(relaxed = true) }
                            single { mockk<AdminUsersRepository>(relaxed = true) }
                            single<TelegramWebhookDedupRepository> { dedupRepository }
                        }
                    )
                }
                installAdminWebhook()
            }

            val response = client.post("/tg/admin") {
                header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.adminWebhookSecret)
                setBody(payload)
            }
            response.status shouldBe HttpStatusCode.Conflict
            response.headers[HttpHeaders.RetryAfter] shouldBe "2"
        }
    }

    "resolve in-progress status keeps default and allows only whitelist" {
        resolveTelegramInProgressStatus { null } shouldBe HttpStatusCode.Conflict
        resolveTelegramInProgressStatus { "429" } shouldBe HttpStatusCode.TooManyRequests
        resolveTelegramInProgressStatus { "503" } shouldBe HttpStatusCode.ServiceUnavailable
        resolveTelegramInProgressStatus { "200" } shouldBe HttpStatusCode.Conflict
        resolveTelegramInProgressStatus { "abc" } shouldBe HttpStatusCode.Conflict
    }

    "in-memory dedup purge deletes old processed and stale processing only" {
        val now = Instant.parse("2026-02-01T00:00:00Z")
        val repository = InMemoryTelegramWebhookDedupRepository().apply {
            seedProcessed(
                botType = TELEGRAM_BOT_TYPE_ADMIN,
                updateId = 8001L,
                createdAt = now.minusSeconds(6_000),
                processedAt = now.minusSeconds(5_000)
            )
            seedProcessing(
                botType = TELEGRAM_BOT_TYPE_ADMIN,
                updateId = 8002L,
                createdAt = now.minusSeconds(4_000)
            )
            seedProcessed(
                botType = TELEGRAM_BOT_TYPE_SHOP,
                updateId = 8003L,
                createdAt = now.minusSeconds(50),
                processedAt = now.minusSeconds(10)
            )
            seedProcessing(
                botType = TELEGRAM_BOT_TYPE_SHOP,
                updateId = 8004L,
                createdAt = now.minusSeconds(100)
            )
        }

        repository.purge(
            processedBefore = now.minusSeconds(1_000),
            staleProcessingBefore = now.minusSeconds(1_000)
        ) shouldBe 2

        acquireTelegramUpdateProcessing(
            dedupRepository = repository,
            botType = TELEGRAM_BOT_TYPE_ADMIN,
            updateId = 8001L,
            logger = mockk(relaxed = true),
            now = now
        ) shouldBe TelegramWebhookDedupDecision.ACQUIRED

        acquireTelegramUpdateProcessing(
            dedupRepository = repository,
            botType = TELEGRAM_BOT_TYPE_ADMIN,
            updateId = 8002L,
            logger = mockk(relaxed = true),
            now = now
        ) shouldBe TelegramWebhookDedupDecision.ACQUIRED

        acquireTelegramUpdateProcessing(
            dedupRepository = repository,
            botType = TELEGRAM_BOT_TYPE_SHOP,
            updateId = 8003L,
            logger = mockk(relaxed = true),
            now = now
        ) shouldBe TelegramWebhookDedupDecision.ALREADY_PROCESSED

        acquireTelegramUpdateProcessing(
            dedupRepository = repository,
            botType = TELEGRAM_BOT_TYPE_SHOP,
            updateId = 8004L,
            logger = mockk(relaxed = true),
            now = now
        ) shouldBe TelegramWebhookDedupDecision.IN_PROGRESS
    }
})

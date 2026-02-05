package com.example.app.routes

import com.example.app.baseTestConfig
import com.example.app.services.InventoryService
import com.example.app.services.ItemsService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.MediaStateStore
import com.example.app.services.OrderStatusService
import com.example.app.services.OffersService
import com.example.app.services.PaymentDetailsStateStore
import com.example.app.services.PaymentRejectReasonStateStore
import com.example.app.services.PostService
import com.example.app.testutil.InMemoryTelegramWebhookDedupRepository
import com.example.bots.InstrumentedTelegramBot
import com.example.bots.TelegramClients
import com.example.db.ItemMediaRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrdersRepository
import com.example.db.TelegramWebhookDedupRepository
import com.pengrad.telegrambot.request.SendMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class TelegramWebhookDedupTest : StringSpec({
    "tryMarkProcessed returns true for first update and false for duplicate" {
        val repository = InMemoryTelegramWebhookDedupRepository()

        repository.tryMarkProcessed(TELEGRAM_BOT_TYPE_ADMIN, 42L, java.time.Instant.now()) shouldBe true
        repository.tryMarkProcessed(TELEGRAM_BOT_TYPE_ADMIN, 42L, java.time.Instant.now()) shouldBe false
    }

    "admin webhook drops duplicate update before side effects" {
        val cfg = baseTestConfig()
        val dedupRepository = InMemoryTelegramWebhookDedupRepository()
        val adminBot = mockk<InstrumentedTelegramBot>()
        every { adminBot.execute(any<SendMessage>()) } returns mockk(relaxed = true)
        val clients = mockk<TelegramClients>()
        every { clients.adminBot } returns adminBot

        val payload = """
            {
              "update_id": 7001,
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
                            single<TelegramWebhookDedupRepository> { dedupRepository }
                        }
                    )
                }
                installAdminWebhook()
            }

            repeat(2) {
                client.post("/tg/admin") {
                    header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.adminWebhookSecret)
                    setBody(payload)
                }.status shouldBe HttpStatusCode.OK
            }
        }

        verify(exactly = 1) { adminBot.execute(any<SendMessage>()) }
    }
})


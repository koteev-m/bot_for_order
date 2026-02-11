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
import com.example.domain.AdminRole
import com.example.domain.AdminUser
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class AdminWebhookPendingFlowRbacTest : StringSpec({
    "readonly admin cannot execute pending reject flow mutations" {
        val adminId = 123456L
        val baseCfg = baseTestConfig()
        val cfg = baseCfg.copy(
            telegram = baseCfg.telegram.copy(adminIds = setOf(adminId))
        )
        val paymentRejectReasonStateStore = PaymentRejectReasonStateStore().apply {
            start(adminId, "order-42")
        }
        val paymentDetailsStateStore = PaymentDetailsStateStore()
        val dedupRepository = InMemoryTelegramWebhookDedupRepository()

        val adminBot = mockk<InstrumentedTelegramBot>()
        val sentMessage = slot<SendMessage>()
        every { adminBot.execute(capture(sentMessage)) } returns mockk<SendResponse>(relaxed = true)

        val clients = mockk<TelegramClients>()
        every { clients.adminBot } returns adminBot

        val adminUsersRepository = mockk<AdminUsersRepository>()
        coEvery { adminUsersRepository.get(cfg.merchants.defaultMerchantId, adminId) } returns AdminUser(
            merchantId = cfg.merchants.defaultMerchantId,
            userId = adminId,
            role = AdminRole.READONLY,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z")
        )

        val manualPaymentsService = mockk<ManualPaymentsService>(relaxed = true)

        val payload = """
            {
              "update_id": 8123,
              "message": {
                "message_id": 1,
                "date": 1710000000,
                "text": "Причина отклонения",
                "from": {"id": $adminId, "first_name": "U"},
                "chat": {"id": $adminId, "type": "private"}
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
                            single { paymentDetailsStateStore }
                            single { paymentRejectReasonStateStore }
                            single { mockk<PostService>(relaxed = true) }
                            single { mockk<OrderStatusService>(relaxed = true) }
                            single { mockk<OffersService>(relaxed = true) }
                            single { mockk<InventoryService>(relaxed = true) }
                            single { manualPaymentsService }
                            single { mockk<OrdersRepository>(relaxed = true) }
                            single { mockk<OrderDeliveryRepository>(relaxed = true) }
                            single { mockk<com.example.db.AuditLogRepository>(relaxed = true) }
                            single { adminUsersRepository }
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

        paymentRejectReasonStateStore.get(adminId) shouldBe null
        coVerify(exactly = 0) { manualPaymentsService.rejectPayment(any(), any(), any()) }
        coVerify(exactly = 0) { manualPaymentsService.setPaymentDetails(any(), any(), any()) }
        coVerify(exactly = 1) {
            adminUsersRepository.get(cfg.merchants.defaultMerchantId, adminId)
        }
        verify(exactly = 1) { adminBot.execute(any<SendMessage>()) }
        sentMessage.captured.parameters["text"] shouldBe "⛔ Команда недоступна."
    }
})

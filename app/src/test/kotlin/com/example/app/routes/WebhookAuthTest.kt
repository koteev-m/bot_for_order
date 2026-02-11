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
import com.example.bots.TelegramClients
import com.example.db.AdminUsersRepository
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.MerchantsRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.db.TelegramWebhookDedupRepository
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.mockk.mockk

class WebhookAuthTest : StringSpec({
    "admin webhook rejects missing or invalid secret" {
        val cfg = baseTestConfig()
        testApplication {
            application {
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { mockk<TelegramClients>(relaxed = true) }
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
                            single { mockk<TelegramWebhookDedupRepository>(relaxed = true) }
                        }
                    )
                }
                installAdminWebhook()
            }

            val missing = client.post("/tg/admin") { setBody("{}") }
            missing.status shouldBe HttpStatusCode.Unauthorized
            val invalid = client.post("/tg/admin") {
                header("X-Telegram-Bot-Api-Secret-Token", "nope")
                setBody("{}")
            }
            invalid.status shouldBe HttpStatusCode.Unauthorized
            val ok = client.post("/tg/admin") {
                header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.adminWebhookSecret)
                setBody("{}")
            }
            ok.status shouldNotBe HttpStatusCode.Unauthorized
        }
    }

    "shop webhook rejects missing or invalid secret" {
        val cfg = baseTestConfig()
        testApplication {
            application {
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { mockk<TelegramClients>(relaxed = true) }
                            single { mockk<ItemsRepository>(relaxed = true) }
                            single { mockk<PricesDisplayRepository>(relaxed = true) }
                            single { mockk<VariantsRepository>(relaxed = true) }
                            single { mockk<OrdersRepository>(relaxed = true) }
                            single { mockk<OrderLinesRepository>(relaxed = true) }
                            single { mockk<MerchantsRepository>(relaxed = true) }
                            single { mockk<OrderStatusHistoryRepository>(relaxed = true) }
                            single { mockk<com.example.app.services.PaymentsService>(relaxed = true) }
                            single { mockk<LockManager>(relaxed = true) }
                            single { mockk<OrderHoldService>(relaxed = true) }
                            single { mockk<HoldService>(relaxed = true) }
                            single { mockk<TelegramWebhookDedupRepository>(relaxed = true) }
                        }
                    )
                }
                installShopWebhook()
            }

            val missing = client.post("/tg/shop") { setBody("{}") }
            missing.status shouldBe HttpStatusCode.Unauthorized
            val invalid = client.post("/tg/shop") {
                header("X-Telegram-Bot-Api-Secret-Token", "nope")
                setBody("{}")
            }
            invalid.status shouldBe HttpStatusCode.Unauthorized
            val ok = client.post("/tg/shop") {
                header("X-Telegram-Bot-Api-Secret-Token", cfg.telegram.shopWebhookSecret)
                setBody("{}")
            }
            ok.status shouldNotBe HttpStatusCode.Unauthorized
        }
    }
})

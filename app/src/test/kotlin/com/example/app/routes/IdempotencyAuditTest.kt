package com.example.app.routes

import com.example.app.api.AdminOrderStatusRequest
import com.example.app.api.PaymentClaimRequest
import com.example.app.api.installApiErrors
import com.example.app.baseTestConfig
import com.example.app.security.TelegramInitDataVerifier
import com.example.app.security.installInitDataAuth
import com.example.app.services.DeliveryService
import com.example.app.services.IdempotencyService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.OrderCheckoutService
import com.example.app.services.OrderWithLines
import com.example.app.services.OrderStatusService
import com.example.app.services.PaymentDetailsCrypto
import com.example.app.services.PaymentsService
import com.example.app.services.PostService
import com.example.app.services.UserActionRateLimiter
import com.example.app.testutil.InMemoryAdminUsersRepository
import com.example.app.testutil.InMemoryAuditLogRepository
import com.example.app.testutil.InMemoryIdempotencyRepository
import com.example.domain.AdminRole
import com.example.domain.AdminUser
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.Order
import com.example.domain.OrderLine
import com.example.domain.OrderPaymentClaim
import com.example.domain.OrderStatus
import com.example.domain.PaymentClaimStatus
import com.example.domain.PaymentMethodType
import com.example.db.AdminUsersRepository
import com.example.db.AuditLogRepository
import com.example.db.ChannelBindingsRepository
import com.example.db.IdempotencyRepository
import com.example.db.ItemsRepository
import com.example.db.MerchantDeliveryMethodsRepository
import com.example.db.MerchantPaymentMethodsRepository
import com.example.db.OrderAttachmentsRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderPaymentClaimsRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.StorefrontsRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class IdempotencyAuditTest : StringSpec({
    "admin confirm payment is idempotent and audits once" {
        val cfg = baseTestConfig()
        val adminUsers = InMemoryAdminUsersRepository()
        val auditLogRepository = InMemoryAuditLogRepository()
        val idempotencyRepository = InMemoryIdempotencyRepository()
        val idempotencyService = IdempotencyService(idempotencyRepository)
        val manualPaymentsService = mockk<ManualPaymentsService>()
        val orderStatusService = mockk<OrderStatusService>()
        val ordersRepository = mockk<OrdersRepository>()
        val orderLinesRepository = mockk<OrderLinesRepository>()
        val orderDeliveryRepository = mockk<OrderDeliveryRepository>()
        val paymentClaimsRepository = mockk<OrderPaymentClaimsRepository>()
        val attachmentsRepository = mockk<OrderAttachmentsRepository>()
        val paymentMethodsRepository = mockk<MerchantPaymentMethodsRepository>(relaxed = true)
        val deliveryMethodsRepository = mockk<MerchantDeliveryMethodsRepository>(relaxed = true)
        val storefrontsRepository = mockk<StorefrontsRepository>(relaxed = true)
        val channelBindingsRepository = mockk<ChannelBindingsRepository>(relaxed = true)
        val postService = mockk<PostService>()
        val paymentDetailsCrypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val initDataVerifier = TelegramInitDataVerifier(cfg.telegram.shopToken, cfg.telegramInitData.maxAgeSeconds)

        val now = Instant.now()
        val order = Order(
            id = "1",
            merchantId = cfg.merchants.defaultMerchantId,
            userId = 10L,
            itemId = "item-1",
            variantId = null,
            qty = 1,
            currency = "RUB",
            amountMinor = 1000,
            deliveryOption = null,
            addressJson = null,
            provider = null,
            providerChargeId = null,
            status = OrderStatus.PAYMENT_UNDER_REVIEW,
            createdAt = now,
            updatedAt = now,
            paymentMethodType = PaymentMethodType.MANUAL_CARD
        )
        coEvery { ordersRepository.get("1") } returns order
        coEvery { manualPaymentsService.confirmPayment("1", any()) } returns order.copy(status = OrderStatus.PAID_CONFIRMED)

        adminUsers.put(
            AdminUser(
                merchantId = cfg.merchants.defaultMerchantId,
                userId = 42L,
                role = AdminRole.OPERATOR,
                createdAt = now,
                updatedAt = now
            )
        )

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { initDataVerifier }
                            single<AdminUsersRepository> { adminUsers }
                            single { ordersRepository }
                            single { orderLinesRepository }
                            single { orderDeliveryRepository }
                            single { paymentClaimsRepository }
                            single { attachmentsRepository }
                            single { paymentMethodsRepository }
                            single { deliveryMethodsRepository }
                            single<StorefrontsRepository> { storefrontsRepository }
                            single<ChannelBindingsRepository> { channelBindingsRepository }
                            single { manualPaymentsService }
                            single { orderStatusService }
                            single { postService }
                            single { paymentDetailsCrypto }
                            single<AuditLogRepository> { auditLogRepository }
                            single<IdempotencyRepository> { idempotencyRepository }
                            single { idempotencyService }
                        }
                    )
                }
                installAdminApiRoutes()
            }

            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(cfg.telegram.shopToken, userId = 42L)
            val first = client.post("/api/admin/orders/1/payment/confirm") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-1")
            }
            val second = client.post("/api/admin/orders/1/payment/confirm") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-1")
            }
            first.status shouldBe HttpStatusCode.OK
            second.status shouldBe HttpStatusCode.OK
            first.bodyAsText() shouldBe second.bodyAsText()
            coVerify(exactly = 1) { manualPaymentsService.confirmPayment("1", 42L) }
            auditLogRepository.entries.size shouldBe 1
        }
    }

    "admin status change is idempotent and audits once" {
        val cfg = baseTestConfig()
        val adminUsers = InMemoryAdminUsersRepository()
        val auditLogRepository = InMemoryAuditLogRepository()
        val idempotencyRepository = InMemoryIdempotencyRepository()
        val idempotencyService = IdempotencyService(idempotencyRepository)
        val manualPaymentsService = mockk<ManualPaymentsService>()
        val orderStatusService = mockk<OrderStatusService>()
        val ordersRepository = mockk<OrdersRepository>()
        val orderLinesRepository = mockk<OrderLinesRepository>()
        val orderDeliveryRepository = mockk<OrderDeliveryRepository>()
        val paymentClaimsRepository = mockk<OrderPaymentClaimsRepository>()
        val attachmentsRepository = mockk<OrderAttachmentsRepository>()
        val paymentMethodsRepository = mockk<MerchantPaymentMethodsRepository>(relaxed = true)
        val deliveryMethodsRepository = mockk<MerchantDeliveryMethodsRepository>(relaxed = true)
        val storefrontsRepository = mockk<StorefrontsRepository>(relaxed = true)
        val channelBindingsRepository = mockk<ChannelBindingsRepository>(relaxed = true)
        val postService = mockk<PostService>()
        val paymentDetailsCrypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val initDataVerifier = TelegramInitDataVerifier(cfg.telegram.shopToken, cfg.telegramInitData.maxAgeSeconds)

        val now = Instant.now()
        val order = Order(
            id = "1",
            merchantId = cfg.merchants.defaultMerchantId,
            userId = 10L,
            itemId = "item-1",
            variantId = null,
            qty = 1,
            currency = "RUB",
            amountMinor = 1000,
            deliveryOption = null,
            addressJson = null,
            provider = null,
            providerChargeId = null,
            status = OrderStatus.PAYMENT_UNDER_REVIEW,
            createdAt = now,
            updatedAt = now,
            paymentMethodType = PaymentMethodType.MANUAL_CARD
        )
        coEvery { ordersRepository.get("1") } returns order
        coEvery { orderStatusService.changeStatus("1", any(), any(), any()) } returns
            OrderStatusService.ChangeResult(order = order.copy(status = OrderStatus.shipped), changed = true)

        adminUsers.put(
            AdminUser(
                merchantId = cfg.merchants.defaultMerchantId,
                userId = 42L,
                role = AdminRole.OPERATOR,
                createdAt = now,
                updatedAt = now
            )
        )

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) {
                    modules(
                        module {
                            single { cfg }
                            single { initDataVerifier }
                            single<AdminUsersRepository> { adminUsers }
                            single { ordersRepository }
                            single { orderLinesRepository }
                            single { orderDeliveryRepository }
                            single { paymentClaimsRepository }
                            single { attachmentsRepository }
                            single { paymentMethodsRepository }
                            single { deliveryMethodsRepository }
                            single<StorefrontsRepository> { storefrontsRepository }
                            single<ChannelBindingsRepository> { channelBindingsRepository }
                            single { manualPaymentsService }
                            single { orderStatusService }
                            single { postService }
                            single { paymentDetailsCrypto }
                            single<AuditLogRepository> { auditLogRepository }
                            single<IdempotencyRepository> { idempotencyRepository }
                            single { idempotencyService }
                        }
                    )
                }
                installAdminApiRoutes()
            }

            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(cfg.telegram.shopToken, userId = 42L)
            val first = client.post("/api/admin/orders/1/status") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-1")
                setBody(AdminOrderStatusRequest(status = OrderStatus.shipped.name))
            }
            val second = client.post("/api/admin/orders/1/status") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-1")
                setBody(AdminOrderStatusRequest(status = OrderStatus.shipped.name))
            }
            first.status shouldBe HttpStatusCode.OK
            second.status shouldBe HttpStatusCode.OK
            first.bodyAsText() shouldBe second.bodyAsText()
            coVerify(exactly = 1) { orderStatusService.changeStatus("1", OrderStatus.shipped, 42L, null) }
            auditLogRepository.entries.size shouldBe 1
        }
    }

    "create order and payment claim are idempotent" {
        val cfg = baseTestConfig()
        val initDataVerifier = TelegramInitDataVerifier(cfg.telegram.shopToken, cfg.telegramInitData.maxAgeSeconds)
        val idempotencyRepository = InMemoryIdempotencyRepository()
        val idempotencyService = IdempotencyService(idempotencyRepository)
        val userActionRateLimiter = UserActionRateLimiter(cfg.userActionRateLimit)
        val itemsRepository = mockk<ItemsRepository>()
        val ordersRepository = mockk<OrdersRepository>(relaxed = true)
        val orderLinesRepository = mockk<OrderLinesRepository>(relaxed = true)
        val historyRepository = mockk<OrderStatusHistoryRepository>(relaxed = true)
        val paymentsService = mockk<PaymentsService>(relaxed = true)
        val manualPaymentsService = mockk<ManualPaymentsService>()
        val orderDeliveryRepository = mockk<OrderDeliveryRepository>(relaxed = true)
        val deliveryService = mockk<DeliveryService>(relaxed = true)
        val orderCheckoutService = mockk<OrderCheckoutService>()

        val now = Instant.now()
        val order = Order(
            id = "o-1",
            merchantId = cfg.merchants.defaultMerchantId,
            userId = 10L,
            itemId = "item-1",
            variantId = null,
            qty = 1,
            currency = "RUB",
            amountMinor = 1000,
            deliveryOption = null,
            addressJson = null,
            provider = null,
            providerChargeId = null,
            status = OrderStatus.pending,
            createdAt = now,
            updatedAt = now,
            paymentMethodType = PaymentMethodType.MANUAL_CARD
        )
        val line = OrderLine(
            orderId = order.id,
            listingId = "item-1",
            variantId = null,
            qty = 1,
            priceSnapshotMinor = 1000,
            currency = "RUB",
            sourceStorefrontId = "sf-1",
            sourceChannelId = 1L,
            sourcePostMessageId = 2
        )
        val secondLine = OrderLine(
            orderId = order.id,
            listingId = "item-2",
            variantId = "v-2",
            qty = 2,
            priceSnapshotMinor = 700,
            currency = "RUB",
            sourceStorefrontId = "sf-1",
            sourceChannelId = 1L,
            sourcePostMessageId = 3
        )
        coEvery { orderCheckoutService.createFromCart(10L, any()) } returns OrderWithLines(order, listOf(line, secondLine))
        coEvery { itemsRepository.getById("item-1") } returns Item(
            id = "item-1",
            merchantId = cfg.merchants.defaultMerchantId,
            title = "Item",
            description = "desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        val claim = OrderPaymentClaim(
            id = 99L,
            orderId = order.id,
            methodType = PaymentMethodType.MANUAL_CARD,
            txid = "tx",
            comment = "ok",
            createdAt = now,
            status = PaymentClaimStatus.SUBMITTED
        )
        coEvery { manualPaymentsService.submitClaim(any(), any(), any(), any(), any()) } returns claim

        val deps = OrderRoutesDeps(
            merchantId = cfg.merchants.defaultMerchantId,
            itemsRepository = itemsRepository,
            ordersRepository = ordersRepository,
            orderLinesRepository = orderLinesRepository,
            historyRepository = historyRepository,
            paymentsService = paymentsService,
            orderCheckoutService = orderCheckoutService,
            manualPaymentsService = manualPaymentsService,
            orderDeliveryRepository = orderDeliveryRepository,
            deliveryService = deliveryService,
            idempotencyService = idempotencyService,
            userActionRateLimiter = userActionRateLimiter
        )

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                routing {
                    route("/api") {
                        installInitDataAuth(initDataVerifier)
                        registerOrdersRoutes(deps)
                    }
                }
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(cfg.telegram.shopToken, userId = 10L)

            val firstOrder = client.post("/api/orders") {
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-1")
            }
            val secondOrder = client.post("/api/orders") {
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-1")
            }
            firstOrder.status shouldBe HttpStatusCode.Accepted
            secondOrder.status shouldBe HttpStatusCode.Accepted
            firstOrder.bodyAsText() shouldBe secondOrder.bodyAsText()
            coVerify(exactly = 1) { orderCheckoutService.createFromCart(10L, any()) }

            val firstClaim = client.post("/api/orders/${order.id}/payment/claim") {
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-2")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(PaymentClaimRequest(txid = "tx", comment = "ok"))
            }
            val secondClaim = client.post("/api/orders/${order.id}/payment/claim") {
                header("X-Telegram-Init-Data", initData)
                header("Idempotency-Key", "key-2")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(PaymentClaimRequest(txid = "tx", comment = "ok"))
            }
            firstClaim.status shouldBe HttpStatusCode.OK
            secondClaim.status shouldBe HttpStatusCode.OK
            firstClaim.bodyAsText() shouldBe secondClaim.bodyAsText()
            coVerify(exactly = 1) { manualPaymentsService.submitClaim(order.id, 10L, "tx", "ok", any()) }
        }
    }
})

private fun buildInitData(botToken: String, userId: Long): String {
    val authDate = Instant.now().epochSecond.toString()
    val queryId = "AAE-1"
    val userJson = """{"id":$userId,"first_name":"Test"}"""

    val dataCheckString = mapOf(
        "auth_date" to authDate,
        "query_id" to queryId,
        "user" to userJson
    ).toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }

    val secretKey = hmacSha256(
        "WebAppData".toByteArray(StandardCharsets.UTF_8),
        botToken.toByteArray(StandardCharsets.UTF_8)
    )
    val hash = hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8)).toHexLower()

    val encodedUser = URLEncoder.encode(userJson, StandardCharsets.UTF_8)
    return listOf(
        "auth_date=$authDate",
        "query_id=$queryId",
        "user=$encodedUser",
        "hash=$hash"
    ).joinToString("&")
}

private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg)
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

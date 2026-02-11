package com.example.app.routes

import com.example.app.api.AdminPaymentDetailsRequest
import com.example.app.api.AdminPaymentMethodsUpdateRequest
import com.example.app.api.AdminPaymentMethodUpdate
import com.example.app.api.AdminPaymentRejectRequest
import com.example.app.api.AdminPublishRequest
import com.example.app.api.AdminOrderStatusRequest
import com.example.app.api.AdminChannelBindingRequest
import com.example.app.api.AdminStorefrontRequest
import com.example.app.api.installApiErrors
import com.example.app.baseTestConfig
import com.example.app.security.TelegramInitDataVerifier
import com.example.app.testutil.InMemoryAdminUsersRepository
import com.example.app.testutil.InMemoryAuditLogRepository
import com.example.app.testutil.InMemoryIdempotencyRepository
import com.example.app.services.IdempotencyService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.OrderStatusService
import com.example.app.services.PaymentDetailsCrypto
import com.example.app.services.PostService
import com.example.domain.AdminRole
import com.example.domain.AdminUser
import com.example.domain.ChannelBinding
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.PaymentMethodType
import com.example.domain.Storefront
import com.example.db.AdminUsersRepository
import com.example.db.AuditLogRepository
import com.example.db.ChannelBindingsRepository
import com.example.db.IdempotencyRepository
import com.example.db.MerchantDeliveryMethodsRepository
import com.example.db.MerchantPaymentMethodsRepository
import com.example.db.OrderAttachmentsRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderPaymentClaimsRepository
import com.example.db.OrdersRepository
import com.example.db.StorefrontsRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.mockk.coEvery
import io.mockk.mockk

class AdminRoutesRbacTest : StringSpec({
    "not in admin_user returns forbidden" {
        val deps = TestAdminDeps()
        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)
            val response = client.post("/api/admin/orders/1/payment/confirm") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
            }
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "operator can act on orders but cannot mutate settings or publish" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.OPERATOR, deps.config.merchants.defaultMerchantId))
        deps.stubOrderActions()

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val confirmResponse = client.post("/api/admin/orders/1/payment/confirm") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
            }
            confirmResponse.status shouldBe HttpStatusCode.OK

            val rejectResponse = client.post("/api/admin/orders/1/payment/reject") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminPaymentRejectRequest(reason = "no proof"))
            }
            rejectResponse.status shouldBe HttpStatusCode.OK

            val statusResponse = client.post("/api/admin/orders/1/status") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminOrderStatusRequest(status = OrderStatus.shipped.name, trackingCode = "TRK-1"))
            }
            statusResponse.status shouldBe HttpStatusCode.OK

            val settingsResponse = client.post("/api/admin/settings/payment_methods") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(
                    AdminPaymentMethodsUpdateRequest(
                        methods = listOf(
                            AdminPaymentMethodUpdate(
                                type = PaymentMethodType.MANUAL_CARD.name,
                                mode = "MANUAL_SEND",
                                enabled = false
                            )
                        )
                    )
                )
            }
            settingsResponse.status shouldBe HttpStatusCode.Forbidden

            val publishResponse = client.post("/api/admin/publications/publish") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminPublishRequest(itemId = "item-1", channelIds = listOf(1L)))
            }
            publishResponse.status shouldBe HttpStatusCode.Forbidden
        }
    }



    "readonly can access read endpoints but forbidden for mutating endpoints" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.READONLY, deps.config.merchants.defaultMerchantId))
        deps.stubOrderActions()

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val meResponse = client.get("/api/admin/me") {
                header("X-Telegram-Init-Data", initData)
            }
            meResponse.status shouldBe HttpStatusCode.OK

            val confirmResponse = client.post("/api/admin/orders/1/payment/confirm") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
            }
            confirmResponse.status shouldBe HttpStatusCode.Forbidden

            val rejectResponse = client.post("/api/admin/orders/1/payment/reject") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminPaymentRejectRequest(reason = "no proof"))
            }
            rejectResponse.status shouldBe HttpStatusCode.Forbidden

            val detailsResponse = client.post("/api/admin/orders/1/payment/details") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminPaymentDetailsRequest(text = "card"))
            }
            detailsResponse.status shouldBe HttpStatusCode.Forbidden

            val statusResponse = client.post("/api/admin/orders/1/status") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminOrderStatusRequest(status = OrderStatus.shipped.name, trackingCode = "TRK-1"))
            }
            statusResponse.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "payments role can mutate manual payments but cannot change order status" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.PAYMENTS, deps.config.merchants.defaultMerchantId))
        deps.stubOrderActions()

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val confirmResponse = client.post("/api/admin/orders/1/payment/confirm") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
            }
            confirmResponse.status shouldBe HttpStatusCode.OK

            val statusResponse = client.post("/api/admin/orders/1/status") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminOrderStatusRequest(status = OrderStatus.shipped.name, trackingCode = "TRK-1"))
            }
            statusResponse.status shouldBe HttpStatusCode.Forbidden
        }
    }
    "owner can mutate settings and publish" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.OWNER, deps.config.merchants.defaultMerchantId))
        deps.stubOrderActions()
        coEvery { deps.postService.postItemAlbumToChannels("item-1", listOf(1L)) } returns listOf(
            PostService.PublishResult(channelId = 1L, ok = true)
        )

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val settingsResponse = client.post("/api/admin/settings/payment_methods") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(
                    AdminPaymentMethodsUpdateRequest(
                        methods = listOf(
                            AdminPaymentMethodUpdate(
                                type = PaymentMethodType.MANUAL_CARD.name,
                                mode = "MANUAL_SEND",
                                enabled = false
                            )
                        )
                    )
                )
            }
            settingsResponse.status shouldBe HttpStatusCode.OK

            val publishResponse = client.post("/api/admin/publications/publish") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminPublishRequest(itemId = "item-1", channelIds = listOf(1L)))
            }
            publishResponse.status shouldBe HttpStatusCode.OK
        }
    }

    "order actions return not found for another merchant" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.OPERATOR, deps.config.merchants.defaultMerchantId))
        deps.adminUsers.put(adminUser(43L, AdminRole.OWNER, deps.config.merchants.defaultMerchantId))
        val now = Instant.now()
        val foreignOrder = Order(
            id = "1",
            merchantId = "other-merchant",
            userId = 10L,
            itemId = null,
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
        coEvery { deps.ordersRepository.get("1") } returns foreignOrder

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

            val operatorInitData = buildInitData(deps.config.telegram.shopToken, userId = 42L)
            val operatorResponse = client.post("/api/admin/orders/1/payment/confirm") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", operatorInitData)
            }
            operatorResponse.status shouldBe HttpStatusCode.NotFound

            val ownerInitData = buildInitData(deps.config.telegram.shopToken, userId = 43L)
            val ownerResponse = client.post("/api/admin/orders/1/status") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", ownerInitData)
                setBody(AdminOrderStatusRequest(status = OrderStatus.shipped.name))
            }
            ownerResponse.status shouldBe HttpStatusCode.NotFound
        }
    }

    "channel bindings upsert with existing channel id returns ok" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.OWNER, deps.config.merchants.defaultMerchantId))
        val storefront = Storefront(
            id = "sf-1",
            merchantId = deps.config.merchants.defaultMerchantId,
            name = "Main"
        )
        coEvery { deps.storefrontsRepository.getById("sf-1") } returns storefront
        coEvery { deps.channelBindingsRepository.getByChannel(100L) } returns null
        coEvery { deps.channelBindingsRepository.upsert("sf-1", 100L, any()) } returns 10L

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val response = client.post("/api/admin/settings/channel_bindings") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminChannelBindingRequest(storefrontId = "sf-1", channelId = 100L))
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "channel bindings takeover is forbidden for another merchant" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.OWNER, deps.config.merchants.defaultMerchantId))
        val storefront = Storefront(
            id = "sf-1",
            merchantId = deps.config.merchants.defaultMerchantId,
            name = "Main"
        )
        val foreignStorefront = Storefront(
            id = "sf-other",
            merchantId = "other-merchant",
            name = "Foreign"
        )
        val existing = ChannelBinding(
            id = 10L,
            storefrontId = "sf-other",
            channelId = 100L,
            createdAt = Instant.now()
        )
        coEvery { deps.storefrontsRepository.getById("sf-1") } returns storefront
        coEvery { deps.storefrontsRepository.getById("sf-other") } returns foreignStorefront
        coEvery { deps.channelBindingsRepository.getByChannel(100L) } returns existing

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val response = client.post("/api/admin/settings/channel_bindings") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminChannelBindingRequest(storefrontId = "sf-1", channelId = 100L))
            }
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "storefront upsert forbidden for another merchant storefront id" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.OWNER, deps.config.merchants.defaultMerchantId))
        val foreignStorefront = Storefront(
            id = "sf-foreign",
            merchantId = "other-merchant",
            name = "Foreign"
        )
        coEvery { deps.storefrontsRepository.getById("sf-foreign") } returns foreignStorefront

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val response = client.post("/api/admin/settings/storefronts") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminStorefrontRequest(id = "sf-foreign", name = "Foreign"))
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    "invalid status returns bad request" {
        val deps = TestAdminDeps()
        deps.adminUsers.put(adminUser(42L, AdminRole.OPERATOR, deps.config.merchants.defaultMerchantId))
        deps.stubOrderActions()

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                install(Koin) { modules(deps.module()) }
                installAdminApiRoutes()
            }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)

            val response = client.post("/api/admin/orders/1/status") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(AdminOrderStatusRequest(status = "NOT_A_STATUS"))
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }
})

private class TestAdminDeps {
    val config = baseTestConfig()
    val adminUsers = InMemoryAdminUsersRepository()
    val ordersRepository = mockk<OrdersRepository>()
    val orderLinesRepository = mockk<OrderLinesRepository>()
    val orderDeliveryRepository = mockk<OrderDeliveryRepository>()
    val orderPaymentClaimsRepository = mockk<OrderPaymentClaimsRepository>()
    val orderAttachmentsRepository = mockk<OrderAttachmentsRepository>()
    val paymentMethodsRepository = mockk<MerchantPaymentMethodsRepository>(relaxed = true)
    val deliveryMethodsRepository = mockk<MerchantDeliveryMethodsRepository>(relaxed = true)
    val storefrontsRepository = mockk<StorefrontsRepository>(relaxed = true)
    val channelBindingsRepository = mockk<ChannelBindingsRepository>(relaxed = true)
    val manualPaymentsService = mockk<ManualPaymentsService>()
    val orderStatusService = mockk<OrderStatusService>()
    val postService = mockk<PostService>()
    val paymentDetailsCrypto = PaymentDetailsCrypto(config.manualPayments.detailsEncryptionKey)
    val initDataVerifier = TelegramInitDataVerifier(config.telegram.shopToken, config.telegramInitData.maxAgeSeconds)
    val auditLogRepository = InMemoryAuditLogRepository()
    val idempotencyRepository = InMemoryIdempotencyRepository()
    val idempotencyService = IdempotencyService(idempotencyRepository)

    fun module() = module {
        single { config }
        single { initDataVerifier }
        single<AdminUsersRepository> { adminUsers }
        single { ordersRepository }
        single { orderLinesRepository }
        single { orderDeliveryRepository }
        single { orderPaymentClaimsRepository }
        single { orderAttachmentsRepository }
        single { paymentMethodsRepository }
        single { deliveryMethodsRepository }
        single { storefrontsRepository }
        single { channelBindingsRepository }
        single { manualPaymentsService }
        single { orderStatusService }
        single { postService }
        single { paymentDetailsCrypto }
        single<AuditLogRepository> { auditLogRepository }
        single<IdempotencyRepository> { idempotencyRepository }
        single { idempotencyService }
    }

    fun stubOrderActions() {
        val now = Instant.now()
        val order = Order(
            id = "1",
            merchantId = config.merchants.defaultMerchantId,
            userId = 10L,
            itemId = null,
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
        coEvery { manualPaymentsService.rejectPayment("1", any(), any()) } returns order.copy(status = OrderStatus.AWAITING_PAYMENT)
        coEvery { orderStatusService.changeStatus("1", any(), any(), any()) } returns
            OrderStatusService.ChangeResult(order = order.copy(status = OrderStatus.shipped), changed = true)
    }
}

private fun adminUser(userId: Long, role: AdminRole, merchantId: String): AdminUser {
    val now = Instant.now()
    return AdminUser(
        merchantId = merchantId,
        userId = userId,
        role = role,
        createdAt = now,
        updatedAt = now
    )
}

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

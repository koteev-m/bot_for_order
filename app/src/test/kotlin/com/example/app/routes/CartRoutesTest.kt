package com.example.app.routes

import com.example.app.api.installApiErrors
import com.example.app.baseTestConfig
import com.example.app.api.CartAddByTokenRequest
import com.example.app.api.CartAddResponse
import com.example.app.api.CartResponse
import com.example.app.api.CartUndoRequest
import com.example.app.api.CartVariantRequiredResponse
import com.example.app.config.AppConfig
import com.example.app.security.TelegramInitDataVerifier
import com.example.app.security.installInitDataAuth
import com.example.app.services.CartService
import com.example.app.services.LinkContextService
import com.example.app.services.LinkTokenHasher
import com.example.app.services.UserActionRateLimiter
import com.example.app.testutil.InMemoryCartItemsRepository
import com.example.app.testutil.InMemoryCartRedisStore
import com.example.app.testutil.InMemoryCartsRepository
import com.example.app.testutil.InMemoryItemsRepository
import com.example.app.testutil.InMemoryLinkContextsRepository
import com.example.app.testutil.InMemoryPricesDisplayRepository
import com.example.app.testutil.InMemoryVariantsRepository
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import com.example.domain.PricesDisplay
import com.example.domain.Variant
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class CartRoutesTest : StringSpec({
    "add_by_token success and get cart" {
        val deps = TestCartRoutesDeps()
        val token = "token-1"
        deps.seedBasicItem(token)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                routing {
                    route("/api") {
                        installInitDataAuth(deps.initDataVerifier)
                        registerCartRoutes(deps.cartService, deps.config, deps.userActionRateLimiter)
                    }
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)
            val addResponse = client.post("/api/cart/add_by_token") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(CartAddByTokenRequest(token = token))
            }
            addResponse.status shouldBe HttpStatusCode.OK
            val addBody = addResponse.body<CartAddResponse>()
            addBody.cart.items.size shouldBe 1

            val getResponse = client.get("/api/cart") {
                header("X-Telegram-Init-Data", initData)
            }
            getResponse.status shouldBe HttpStatusCode.OK
            val cartBody = getResponse.body<CartResponse>()
            cartBody.cart.items.size shouldBe 1
        }
    }

    "variant_required response" {
        val deps = TestCartRoutesDeps()
        val token = "token-2"
        deps.seedMultiVariantItem(token)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                routing {
                    route("/api") {
                        installInitDataAuth(deps.initDataVerifier)
                        registerCartRoutes(deps.cartService, deps.config, deps.userActionRateLimiter)
                    }
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)
            val response = client.post("/api/cart/add_by_token") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(CartAddByTokenRequest(token = token))
            }
            response.status shouldBe HttpStatusCode.OK
            val body = response.body<CartVariantRequiredResponse>()
            body.status shouldBe "variant_required"
        }
    }

    "update qty and remove" {
        val deps = TestCartRoutesDeps()
        val token = "token-3"
        deps.seedBasicItem(token)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                routing {
                    route("/api") {
                        installInitDataAuth(deps.initDataVerifier)
                        registerCartRoutes(deps.cartService, deps.config, deps.userActionRateLimiter)
                    }
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)
            val addResponse = client.post("/api/cart/add_by_token") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(CartAddByTokenRequest(token = token))
            }
            val addBody = addResponse.body<CartAddResponse>()
            val lineId = addBody.addedLineId

            val updateResponse = client.post("/api/cart/update") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(buildJsonObject {
                    put("lineId", JsonPrimitive(lineId))
                    put("qty", JsonPrimitive(2))
                })
            }
            val updateBody = updateResponse.body<CartResponse>()
            updateBody.cart.items.single().qty shouldBe 2

            val removeResponse = client.post("/api/cart/update") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(buildJsonObject {
                    put("lineId", JsonPrimitive(lineId))
                    put("remove", JsonPrimitive(true))
                })
            }
            val removeBody = removeResponse.body<CartResponse>()
            removeBody.cart.items.size shouldBe 0
        }
    }

    "undo removes line" {
        val deps = TestCartRoutesDeps()
        val token = "token-4"
        deps.seedBasicItem(token)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                routing {
                    route("/api") {
                        installInitDataAuth(deps.initDataVerifier)
                        registerCartRoutes(deps.cartService, deps.config, deps.userActionRateLimiter)
                    }
                }
            }
            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val initData = buildInitData(deps.config.telegram.shopToken, userId = 42L)
            val addResponse = client.post("/api/cart/add_by_token") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(CartAddByTokenRequest(token = token))
            }
            val addBody = addResponse.body<CartAddResponse>()

            val undoResponse = client.post("/api/cart/undo") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(CartUndoRequest(addBody.undoToken))
            }
            val undoBody = undoResponse.body<CartResponse>()
            undoBody.cart.items.size shouldBe 0
        }
    }
})

private class TestCartRoutesDeps {
    val config: AppConfig = baseTestConfig().copy(
        telegram = baseTestConfig().telegram.copy(shopToken = "test:token")
    )
    private val tokenHasher = LinkTokenHasher("secret")
    private val linkContexts = InMemoryLinkContextsRepository()
    private val items = InMemoryItemsRepository()
    private val variants = InMemoryVariantsRepository()
    private val prices = InMemoryPricesDisplayRepository()
    private val carts = InMemoryCartsRepository()
    private val cartItems = InMemoryCartItemsRepository(carts::getById)
    private val redisStore = InMemoryCartRedisStore(Instant::now)
    private val linkContextService = LinkContextService(linkContexts, tokenHasher)
    val cartService = CartService(
        config = config,
        linkContextService = linkContextService,
        itemsRepository = items,
        variantsRepository = variants,
        pricesDisplayRepository = prices,
        cartsRepository = carts,
        cartItemsRepository = cartItems,
        cartRedisStore = redisStore,
        tokenHasher = tokenHasher
    )
    val initDataVerifier = TelegramInitDataVerifier(config.telegram.shopToken, config.telegramInitData.maxAgeSeconds)
    val userActionRateLimiter = UserActionRateLimiter(config.userActionRateLimit)

    suspend fun seedBasicItem(token: String) {
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val merchantId = config.merchants.defaultMerchantId
        linkContexts.create(
            LinkContext(
                id = 0,
                tokenHash = tokenHasher.hash(token),
                merchantId = merchantId,
                storefrontId = "sf1",
                channelId = 10,
                postMessageId = 20,
                listingId = "item-1",
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                createdAt = now,
                revokedAt = null,
                expiresAt = null,
                metadataJson = "{}"
            )
        )
        items.create(
            Item(
                id = "item-1",
                merchantId = merchantId,
                title = "Item",
                description = "Desc",
                status = ItemStatus.active,
                allowBargain = false
            )
        )
        variants.upsert(
            Variant(
                id = "v1",
                itemId = "item-1",
                size = null,
                sku = null,
                stock = 5,
                active = true
            )
        )
        prices.upsert(
            PricesDisplay(
                itemId = "item-1",
                baseCurrency = "USD",
                baseAmountMinor = 1000,
                invoiceCurrencyAmountMinor = null,
                displayRub = null,
                displayUsd = null,
                displayEur = null,
                displayUsdtTs = null,
                fxSource = null
            )
        )
    }

    suspend fun seedMultiVariantItem(token: String) {
        seedBasicItem(token)
        variants.upsert(
            Variant(
                id = "v2",
                itemId = "item-1",
                size = "L",
                sku = null,
                stock = 3,
                active = true
            )
        )
    }
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

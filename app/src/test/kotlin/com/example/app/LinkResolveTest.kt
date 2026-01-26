package com.example.app

import com.example.app.api.LinkResolveRequest
import com.example.app.api.LinkResolveResponse
import com.example.app.api.installApiErrors
import com.example.app.routes.registerLinkRoutes
import com.example.app.services.LinkContextService
import com.example.app.services.LinkTokenGenerator
import com.example.db.LinkContextRepository
import com.example.db.ItemsRepository
import com.example.db.VariantsRepository
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import com.example.domain.Variant
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.serialization.json.Json

class LinkResolveTest : StringSpec({
    "resolve returns link context and item summary" {
        val item = Item(
            id = "item-1",
            title = "Test item",
            description = "Desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        val linkContext = LinkContext(
            id = 1,
            token = "token-1",
            merchantId = "m1",
            storefrontId = "s1",
            channelId = 10,
            postId = 20,
            button = LinkButton.BUY,
            action = LinkAction.buy_now,
            itemId = item.id,
            variantHint = null,
            createdAt = Instant.now(),
            expiresAt = null,
            revokedAt = null,
            metaJson = null
        )

        testApplication {
            val repository = InMemoryLinkContextRepository(mapOf(linkContext.token to linkContext))
            val service = LinkContextService(repository, FixedTokenGenerator())
            val itemsRepo = InMemoryItemsRepository(mapOf(item.id to item))
            val variantsRepo = InMemoryVariantsRepository(mapOf(item.id to emptyList()))

            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(StatusPages) { installApiErrors(this@application.environment.log) }
                routing {
                    route("/api") {
                        registerLinkRoutes(service, itemsRepo, variantsRepo)
                    }
                }
            }

            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.post("/api/link/resolve") {
                contentType(ContentType.Application.Json)
                header("X-User-Id", "42")
                setBody(LinkResolveRequest(token = linkContext.token))
            }
            response.status shouldBe HttpStatusCode.OK
            val payload = response.body<LinkResolveResponse>()
            payload.action shouldBe "buy_now"
            payload.item?.itemId shouldBe item.id
            payload.source.channelId shouldBe 10
        }
    }

    "resolve returns gone for revoked token" {
        val linkContext = LinkContext(
            id = 1,
            token = "revoked",
            merchantId = null,
            storefrontId = null,
            channelId = null,
            postId = null,
            button = LinkButton.BUY,
            action = LinkAction.buy_now,
            itemId = "item-1",
            variantHint = null,
            createdAt = Instant.now(),
            expiresAt = null,
            revokedAt = Instant.now(),
            metaJson = null
        )

        testApplication {
            val repository = InMemoryLinkContextRepository(mapOf(linkContext.token to linkContext))
            val service = LinkContextService(repository, FixedTokenGenerator())
            val itemsRepo = InMemoryItemsRepository(emptyMap())
            val variantsRepo = InMemoryVariantsRepository(emptyMap())

            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(StatusPages) { installApiErrors(this@application.environment.log) }
                routing {
                    route("/api") {
                        registerLinkRoutes(service, itemsRepo, variantsRepo)
                    }
                }
            }

            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.post("/api/link/resolve") {
                contentType(ContentType.Application.Json)
                header("X-User-Id", "42")
                setBody(LinkResolveRequest(token = linkContext.token))
            }
            response.status shouldBe HttpStatusCode.Gone
        }
    }

    "resolve supports legacy startapp tokens" {
        val item = Item(
            id = "legacy-item",
            title = "Legacy item",
            description = "Legacy",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        val token = com.example.bots.startapp.StartAppCodec.encode(
            com.example.bots.startapp.StartAppParam(itemId = item.id)
        )

        testApplication {
            val repository = InMemoryLinkContextRepository(emptyMap())
            val service = LinkContextService(repository, FixedTokenGenerator())
            val itemsRepo = InMemoryItemsRepository(mapOf(item.id to item))
            val variantsRepo = InMemoryVariantsRepository(mapOf(item.id to emptyList()))

            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(StatusPages) { installApiErrors(this@application.environment.log) }
                routing {
                    route("/api") {
                        registerLinkRoutes(service, itemsRepo, variantsRepo)
                    }
                }
            }

            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val response = client.post("/api/link/resolve") {
                contentType(ContentType.Application.Json)
                header("X-User-Id", "42")
                setBody(LinkResolveRequest(token = token))
            }
            response.status shouldBe HttpStatusCode.OK
            val payload = response.body<LinkResolveResponse>()
            payload.legacy shouldBe true
            payload.item?.itemId shouldBe item.id
        }
    }
})

private class InMemoryLinkContextRepository(
    private val records: Map<String, LinkContext>
) : LinkContextRepository {
    override suspend fun create(context: LinkContext): Long {
        error("not used")
    }

    override suspend fun getByToken(token: String): LinkContext? = records[token]
}

private class InMemoryItemsRepository(
    private val records: Map<String, Item>
) : ItemsRepository {
    override suspend fun create(item: Item) {
        error("not used")
    }

    override suspend fun getById(id: String): Item? = records[id]

    override suspend fun setStatus(
        id: String,
        status: ItemStatus,
        allowBargain: Boolean,
        bargainRules: com.example.domain.BargainRules?
    ) {
        error("not used")
    }

    override suspend fun listActive(): List<Item> {
        error("not used")
    }
}

private class InMemoryVariantsRepository(
    private val records: Map<String, List<Variant>>
) : VariantsRepository {
    override suspend fun upsert(variant: Variant) {
        error("not used")
    }

    override suspend fun listByItem(itemId: String): List<Variant> = records[itemId].orEmpty()

    override suspend fun setStock(variantId: String, stock: Int): com.example.db.StockChange? {
        error("not used")
    }

    override suspend fun getById(id: String): Variant? {
        error("not used")
    }

    override suspend fun decrementStock(variantId: String, qty: Int): Boolean {
        error("not used")
    }
}

private class FixedTokenGenerator : LinkTokenGenerator {
    override fun generate(): String = "fixed"
}

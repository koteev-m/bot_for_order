package com.example.app.services

import com.example.app.baseTestConfig
import com.example.app.api.ApiError
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class CartServiceTest : StringSpec({
    "quick-add auto variant uses single purchasable" {
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val deps = cartTestDeps(now)
        val token = "token-1"
        deps.linkContexts.create(
            LinkContext(
                id = 0,
                tokenHash = deps.tokenHasher.hash(token),
                merchantId = "m1",
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
        deps.items.create(
            Item(
                id = "item-1",
                merchantId = "m1",
                title = "Item",
                description = "Desc",
                status = ItemStatus.active,
                allowBargain = false
            )
        )
        deps.variants.upsert(
            Variant(
                id = "v1",
                itemId = "item-1",
                size = "M",
                sku = "SKU1",
                stock = 5,
                active = true
            )
        )
        deps.prices.upsert(
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

        val result = deps.service.addByToken(42, token, now = now)

        val added = result as CartAddResult.Added
        added.cart.items.single().variantId shouldBe "v1"
    }

    "variant_required when multiple purchasable variants" {
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val deps = cartTestDeps(now)
        val token = "token-2"
        deps.linkContexts.create(
            LinkContext(
                id = 0,
                tokenHash = deps.tokenHasher.hash(token),
                merchantId = "m1",
                storefrontId = "sf1",
                channelId = 10,
                postMessageId = null,
                listingId = "item-1",
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                createdAt = now,
                revokedAt = null,
                expiresAt = null,
                metadataJson = "{}"
            )
        )
        deps.items.create(
            Item(
                id = "item-1",
                merchantId = "m1",
                title = "Item",
                description = "Desc",
                status = ItemStatus.active,
                allowBargain = false
            )
        )
        deps.variants.upsert(
            Variant(
                id = "v1",
                itemId = "item-1",
                size = "M",
                sku = null,
                stock = 5,
                active = true
            )
        )
        deps.variants.upsert(
            Variant(
                id = "v2",
                itemId = "item-1",
                size = "L",
                sku = null,
                stock = 3,
                active = true
            )
        )
        deps.prices.upsert(
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

        val result = deps.service.addByToken(42, token, now = now)

        val required = result as CartAddResult.VariantRequired
        required.requiredOptions.variantRequired shouldBe true
        deps.cartItems.listByCart(deps.carts.getOrCreate("m1", 42, now).id).size shouldBe 0
    }

    "selectedVariantId rejects unavailable variant" {
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val deps = cartTestDeps(now)
        val token = "token-3"
        deps.linkContexts.create(
            LinkContext(
                id = 0,
                tokenHash = deps.tokenHasher.hash(token),
                merchantId = "m1",
                storefrontId = "sf1",
                channelId = 10,
                postMessageId = null,
                listingId = "item-1",
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                createdAt = now,
                revokedAt = null,
                expiresAt = null,
                metadataJson = "{}"
            )
        )
        deps.items.create(
            Item(
                id = "item-1",
                merchantId = "m1",
                title = "Item",
                description = "Desc",
                status = ItemStatus.active,
                allowBargain = false
            )
        )
        deps.variants.upsert(
            Variant(
                id = "v1",
                itemId = "item-1",
                size = null,
                sku = null,
                stock = 0,
                active = true
            )
        )
        deps.prices.upsert(
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

        val error = shouldThrow<ApiError> {
            deps.service.addByToken(42, token, selectedVariantId = "v1", now = now)
        }
        error.status shouldBe io.ktor.http.HttpStatusCode.Conflict
    }

    "undo deletes line and second undo expires" {
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val deps = cartTestDeps(now)
        val token = "token-4"
        setupBasicItem(deps, token, now)

        val added = deps.service.addByToken(42, token, now = now) as CartAddResult.Added
        val afterUndo = deps.service.undo(42, added.undoToken, now = now)
        afterUndo.items.size shouldBe 0

        shouldThrow<ApiError> {
            deps.service.undo(42, added.undoToken, now = now)
        }
    }

    "dedup returns same undo token and avoids duplicate lines" {
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val deps = cartTestDeps(now)
        val token = "token-5"
        setupBasicItem(deps, token, now)

        val first = deps.service.addByToken(42, token, now = now) as CartAddResult.Added
        val second = deps.service.addByToken(42, token, now = now) as CartAddResult.Added

        first.undoToken shouldBe second.undoToken
        first.addedLineId shouldBe second.addedLineId
        deps.cartItems.listByCart(deps.carts.getOrCreate("m1", 42, now).id).size shouldBe 1
    }
})

private data class CartTestDeps(
    val service: CartService,
    val tokenHasher: LinkTokenHasher,
    val linkContexts: InMemoryLinkContextsRepository,
    val items: InMemoryItemsRepository,
    val variants: InMemoryVariantsRepository,
    val prices: InMemoryPricesDisplayRepository,
    val carts: InMemoryCartsRepository,
    val cartItems: InMemoryCartItemsRepository
)

private fun cartTestDeps(now: Instant): CartTestDeps {
    val config = baseTestConfig()
    val tokenHasher = LinkTokenHasher("secret")
    val linkContexts = InMemoryLinkContextsRepository()
    val items = InMemoryItemsRepository()
    val variants = InMemoryVariantsRepository()
    val prices = InMemoryPricesDisplayRepository()
    val carts = InMemoryCartsRepository()
    val cartItems = InMemoryCartItemsRepository(carts::getById)
    val redisStore = InMemoryCartRedisStore { now }
    val linkContextService = LinkContextService(linkContexts, tokenHasher)
    val service = CartService(
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
    return CartTestDeps(service, tokenHasher, linkContexts, items, variants, prices, carts, cartItems)
}

private suspend fun setupBasicItem(deps: CartTestDeps, token: String, now: Instant) {
    deps.linkContexts.create(
        LinkContext(
            id = 0,
            tokenHash = deps.tokenHasher.hash(token),
            merchantId = "m1",
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
    deps.items.create(
        Item(
            id = "item-1",
            merchantId = "m1",
            title = "Item",
            description = "Desc",
            status = ItemStatus.active,
            allowBargain = false
        )
    )
    deps.variants.upsert(
        Variant(
            id = "v1",
            itemId = "item-1",
            size = null,
            sku = null,
            stock = 5,
            active = true
        )
    )
    deps.prices.upsert(
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

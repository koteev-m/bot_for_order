package com.example.app.services

import com.example.db.ItemsRepository
import com.example.db.LinkContextsRepository
import com.example.db.StockChange
import com.example.db.VariantsRepository
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import com.example.domain.Variant
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class LinkResolveServiceTest : StringSpec({
    "resolve returns response for valid token" {
        val token = "token-1"
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val hasher = LinkTokenHasher("secret")
        val contextsRepository = InMemoryLinkContextsRepository()
        val itemsRepository = InMemoryItemsRepository()
        val variantsRepository = InMemoryVariantsRepository()
        val linkContextService = LinkContextService(contextsRepository, hasher)
        val service = LinkResolveService(linkContextService, itemsRepository, variantsRepository)

        contextsRepository.create(
            LinkContext(
                id = 0,
                tokenHash = hasher.hash(token),
                merchantId = "merchant-1",
                storefrontId = "storefront-1",
                channelId = 123L,
                postMessageId = 456,
                listingId = "item-1",
                action = LinkAction.BUY,
                button = LinkButton.BUY,
                createdAt = now,
                revokedAt = null,
                expiresAt = null,
                metadataJson = "{}"
            )
        )
        itemsRepository.create(
            Item(
                id = "item-1",
                merchantId = "merchant-1",
                title = "Title",
                description = "Description",
                status = ItemStatus.active,
                allowBargain = false
            )
        )
        variantsRepository.upsert(
            Variant(
                id = "v1",
                itemId = "item-1",
                size = "M",
                sku = "SKU1",
                stock = 5,
                active = true
            )
        )
        variantsRepository.upsert(
            Variant(
                id = "v2",
                itemId = "item-1",
                size = "L",
                sku = "SKU2",
                stock = 0,
                active = true
            )
        )

        val response = service.resolve(token, now)

        response.action shouldBe LinkAction.BUY
        response.listing.id shouldBe "item-1"
        response.source.storefront shouldBe "storefront-1"
        response.source.channel shouldBe 123L
        response.source.post shouldBe 456
        response.source.button shouldBe LinkButton.BUY
        response.availableVariants.first { it.id == "v1" }.available shouldBe true
        response.availableVariants.first { it.id == "v2" }.available shouldBe false
        response.requiredOptions.variantRequired shouldBe false
        response.requiredOptions.autoVariantId shouldBe "v1"
    }

    "resolve rejects revoked token" {
        val token = "token-2"
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val hasher = LinkTokenHasher("secret")
        val contextsRepository = InMemoryLinkContextsRepository()
        val linkContextService = LinkContextService(contextsRepository, hasher)
        val service = LinkResolveService(linkContextService, InMemoryItemsRepository(), InMemoryVariantsRepository())

        contextsRepository.create(
            LinkContext(
                id = 0,
                tokenHash = hasher.hash(token),
                merchantId = "merchant-1",
                storefrontId = "storefront-1",
                channelId = 123L,
                postMessageId = 456,
                listingId = "item-1",
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                createdAt = now,
                revokedAt = now,
                expiresAt = null,
                metadataJson = "{}"
            )
        )

        shouldThrow<LinkResolveException> {
            service.resolve(token, now)
        }
    }

    "resolve rejects expired token" {
        val token = "token-3"
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val hasher = LinkTokenHasher("secret")
        val contextsRepository = InMemoryLinkContextsRepository()
        val linkContextService = LinkContextService(contextsRepository, hasher)
        val service = LinkResolveService(linkContextService, InMemoryItemsRepository(), InMemoryVariantsRepository())

        contextsRepository.create(
            LinkContext(
                id = 0,
                tokenHash = hasher.hash(token),
                merchantId = "merchant-1",
                storefrontId = "storefront-1",
                channelId = 123L,
                postMessageId = null,
                listingId = "item-1",
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                createdAt = now.minusSeconds(120),
                revokedAt = null,
                expiresAt = now,
                metadataJson = "{}"
            )
        )

        shouldThrow<LinkResolveException> {
            service.resolve(token, now)
        }
    }

    "resolve requires variant when multiple purchasable" {
        val token = "token-4"
        val now = Instant.parse("2024-01-02T00:00:00Z")
        val hasher = LinkTokenHasher("secret")
        val contextsRepository = InMemoryLinkContextsRepository()
        val itemsRepository = InMemoryItemsRepository()
        val variantsRepository = InMemoryVariantsRepository()
        val linkContextService = LinkContextService(contextsRepository, hasher)
        val service = LinkResolveService(linkContextService, itemsRepository, variantsRepository)

        contextsRepository.create(
            LinkContext(
                id = 0,
                tokenHash = hasher.hash(token),
                merchantId = "merchant-1",
                storefrontId = "storefront-1",
                channelId = 123L,
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
        itemsRepository.create(
            Item(
                id = "item-1",
                merchantId = "merchant-1",
                title = "Title",
                description = "Description",
                status = ItemStatus.active,
                allowBargain = false
            )
        )
        variantsRepository.upsert(
            Variant(
                id = "v1",
                itemId = "item-1",
                size = null,
                sku = null,
                stock = 5,
                active = true
            )
        )
        variantsRepository.upsert(
            Variant(
                id = "v2",
                itemId = "item-1",
                size = null,
                sku = null,
                stock = 2,
                active = true
            )
        )

        val response = service.resolve(token, now)

        response.requiredOptions.variantRequired shouldBe true
        response.requiredOptions.autoVariantId shouldBe null
    }
})

private class InMemoryLinkContextsRepository : LinkContextsRepository {
    private val storage = mutableMapOf<String, LinkContext>()
    private var nextId = 1L

    override suspend fun create(context: LinkContext): Long {
        val id = if (context.id == 0L) nextId++ else context.id
        storage[context.tokenHash] = context.copy(id = id)
        return id
    }

    override suspend fun getByTokenHash(tokenHash: String): LinkContext? {
        return storage[tokenHash]
    }

    override suspend fun revokeByTokenHash(tokenHash: String, revokedAt: Instant): Boolean {
        val existing = storage[tokenHash] ?: return false
        storage[tokenHash] = existing.copy(revokedAt = revokedAt)
        return true
    }
}

private class InMemoryItemsRepository : ItemsRepository {
    private val storage = mutableMapOf<String, Item>()

    override suspend fun create(item: Item) {
        storage[item.id] = item
    }

    override suspend fun getById(id: String): Item? {
        return storage[id]
    }

    override suspend fun setStatus(id: String, status: ItemStatus, allowBargain: Boolean, bargainRules: com.example.domain.BargainRules?) {
        val existing = storage[id] ?: return
        storage[id] = existing.copy(status = status, allowBargain = allowBargain, bargainRules = bargainRules)
    }

    override suspend fun listActive(): List<Item> {
        return storage.values.filter { it.status == ItemStatus.active }
    }
}

private class InMemoryVariantsRepository : VariantsRepository {
    private val storage = mutableMapOf<String, Variant>()

    override suspend fun upsert(variant: Variant) {
        storage[variant.id] = variant
    }

    override suspend fun listByItem(itemId: String): List<Variant> {
        return storage.values.filter { it.itemId == itemId }
    }

    override suspend fun setStock(variantId: String, stock: Int): StockChange? {
        val existing = storage[variantId] ?: return null
        val updated = existing.copy(stock = stock)
        storage[variantId] = updated
        return StockChange(
            variantId = existing.id,
            itemId = existing.itemId,
            oldStock = existing.stock,
            newStock = stock
        )
    }

    override suspend fun getById(id: String): Variant? {
        return storage[id]
    }

    override suspend fun decrementStock(variantId: String, qty: Int): Boolean {
        val existing = storage[variantId] ?: return false
        if (existing.stock < qty) return false
        storage[variantId] = existing.copy(stock = existing.stock - qty)
        return true
    }

    override suspend fun decrementStockBatch(variantQty: Map<String, Int>): Boolean {
        if (variantQty.isEmpty()) return true
        val snapshot = storage.toMutableMap()
        variantQty.forEach { (variantId, qty) ->
            val existing = snapshot[variantId] ?: return false
            if (!existing.active || existing.stock < qty) return false
            snapshot[variantId] = existing.copy(stock = existing.stock - qty)
        }
        storage.clear()
        storage.putAll(snapshot)
        return true
    }
}

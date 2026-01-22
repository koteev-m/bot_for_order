package com.example.app.testutil

import com.example.app.services.CartRedisStore
import com.example.db.CartItemsRepository
import com.example.db.CartItemWithCart
import com.example.db.CartsRepository
import com.example.db.ItemsRepository
import com.example.db.LinkContextsRepository
import com.example.db.PricesDisplayRepository
import com.example.db.StockChange
import com.example.db.VariantsRepository
import com.example.domain.BargainRules
import com.example.domain.Cart
import com.example.domain.CartItem
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.LinkContext
import com.example.domain.PricesDisplay
import com.example.domain.Variant
import java.time.Instant

class InMemoryLinkContextsRepository : LinkContextsRepository {
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

class InMemoryItemsRepository : ItemsRepository {
    private val storage = mutableMapOf<String, Item>()

    override suspend fun create(item: Item) {
        storage[item.id] = item
    }

    override suspend fun getById(id: String): Item? {
        return storage[id]
    }

    override suspend fun setStatus(id: String, status: ItemStatus, allowBargain: Boolean, bargainRules: BargainRules?) {
        val existing = storage[id] ?: return
        storage[id] = existing.copy(status = status, allowBargain = allowBargain, bargainRules = bargainRules)
    }

    override suspend fun listActive(): List<Item> {
        return storage.values.filter { it.status == ItemStatus.active }
    }
}

class InMemoryVariantsRepository : VariantsRepository {
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
}

class InMemoryPricesDisplayRepository : PricesDisplayRepository {
    private val storage = mutableMapOf<String, PricesDisplay>()

    override suspend fun upsert(p: PricesDisplay) {
        storage[p.itemId] = p
    }

    override suspend fun get(itemId: String): PricesDisplay? {
        return storage[itemId]
    }
}

class InMemoryCartsRepository : CartsRepository {
    private val storage = mutableMapOf<Pair<String, Long>, Cart>()
    private val idIndex = mutableMapOf<Long, Cart>()
    private var nextId = 1L

    override suspend fun getByMerchantAndBuyer(merchantId: String, buyerUserId: Long): Cart? {
        return storage[merchantId to buyerUserId]
    }

    override suspend fun getOrCreate(merchantId: String, buyerUserId: Long, now: Instant): Cart {
        val key = merchantId to buyerUserId
        val existing = storage[key]
        if (existing != null) return existing
        val cart = Cart(
            id = nextId++,
            merchantId = merchantId,
            buyerUserId = buyerUserId,
            createdAt = now,
            updatedAt = now
        )
        storage[key] = cart
        idIndex[cart.id] = cart
        return cart
    }

    override suspend fun touch(cartId: Long, now: Instant) {
        val existing = idIndex[cartId] ?: return
        val updated = existing.copy(updatedAt = now)
        storage[existing.merchantId to existing.buyerUserId] = updated
        idIndex[cartId] = updated
    }

    fun getById(cartId: Long): Cart? = idIndex[cartId]
}

class InMemoryCartItemsRepository(
    private val cartLookup: (Long) -> Cart?
) : CartItemsRepository {
    private val storage = mutableMapOf<Long, CartItem>()
    private var nextId = 1L

    override suspend fun listByCart(cartId: Long): List<CartItem> {
        return storage.values.filter { it.cartId == cartId }.sortedBy { it.createdAt }
    }

    override suspend fun getById(id: Long): CartItem? {
        return storage[id]
    }

    override suspend fun create(item: CartItem): Long {
        val id = if (item.id == 0L) nextId++ else item.id
        storage[id] = item.copy(id = id)
        return id
    }

    override suspend fun updateQty(lineId: Long, qty: Int) {
        val existing = storage[lineId] ?: return
        storage[lineId] = existing.copy(qty = qty)
    }

    override suspend fun updateVariant(lineId: Long, variantId: String?, priceSnapshotMinor: Long, currency: String) {
        val existing = storage[lineId] ?: return
        storage[lineId] = existing.copy(
            variantId = variantId,
            priceSnapshotMinor = priceSnapshotMinor,
            currency = currency
        )
    }

    override suspend fun delete(lineId: Long): Boolean {
        return storage.remove(lineId) != null
    }

    override suspend fun getLineWithCart(lineId: Long): CartItemWithCart? {
        val item = storage[lineId] ?: return null
        val cart = cartLookup(item.cartId) ?: return null
        return CartItemWithCart(item = item, cart = cart)
    }
}

class InMemoryCartRedisStore(
    private val nowProvider: () -> Instant
) : CartRedisStore {
    private val dedup = mutableMapOf<String, StoredValue>()
    private val undo = mutableMapOf<String, StoredValue>()

    override fun tryRegisterDedup(key: String, value: String, ttlSec: Int): String? {
        val now = nowProvider()
        val existing = dedup[key]
        if (existing != null && existing.expiresAt.isAfter(now)) {
            return existing.value
        }
        if (existing != null) {
            dedup.remove(key)
        }
        dedup[key] = StoredValue(value, now.plusSeconds(ttlSec.toLong()))
        return null
    }

    override fun saveUndo(undoToken: String, cartItemId: Long, ttlSec: Int) {
        val now = nowProvider()
        undo[undoToken] = StoredValue(cartItemId.toString(), now.plusSeconds(ttlSec.toLong()))
    }

    override fun consumeUndo(undoToken: String): Long? {
        val now = nowProvider()
        val entry = undo[undoToken] ?: return null
        if (!entry.expiresAt.isAfter(now)) {
            undo.remove(undoToken)
            return null
        }
        undo.remove(undoToken)
        return entry.value.toLongOrNull()
    }

    private data class StoredValue(
        val value: String,
        val expiresAt: Instant
    )
}

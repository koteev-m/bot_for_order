package com.example.db

import com.example.domain.BargainRules
import com.example.domain.Item
import com.example.domain.ItemMedia
import com.example.domain.ItemStatus
import com.example.domain.Offer
import com.example.domain.OfferStatus
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.Post
import com.example.domain.PricesDisplay
import com.example.domain.Variant
import com.example.domain.WatchEntry
import com.example.domain.WatchTrigger
import java.time.Instant

interface ItemsRepository {
    suspend fun create(item: Item)
    suspend fun getById(id: String): Item?
    suspend fun setStatus(id: String, status: ItemStatus, allowBargain: Boolean, bargainRules: BargainRules?)
    suspend fun listActive(): List<Item>
}

interface ItemMediaRepository {
    suspend fun add(media: ItemMedia): Long
    suspend fun listByItem(itemId: String): List<ItemMedia>
    suspend fun deleteByItem(itemId: String)
}

interface VariantsRepository {
    suspend fun upsert(variant: Variant)
    suspend fun listByItem(itemId: String): List<Variant>
    suspend fun setStock(variantId: String, stock: Int)
    suspend fun getById(id: String): Variant?
    suspend fun decrementStock(variantId: String, qty: Int): Boolean
}

interface PricesDisplayRepository {
    suspend fun upsert(p: PricesDisplay)
    suspend fun get(itemId: String): PricesDisplay?
}

interface PostsRepository {
    suspend fun insert(post: Post): Long
    suspend fun listByItem(itemId: String): List<Post>
}

interface OffersRepository {
    suspend fun create(offer: Offer)
    suspend fun get(id: String): Offer?
    suspend fun findActiveByUserAndItem(userId: Long, itemId: String, variantId: String?): Offer?
    suspend fun updateStatusAndCounters(
        id: String,
        status: OfferStatus,
        countersUsed: Int,
        lastCounterAmount: Long?,
        expiresAt: Instant?
    )
    suspend fun updateCounter(id: String, amountMinor: Long, expiresAt: Instant)
    suspend fun expireWhereDue(now: Instant): Int
}

fun OffersRepository.canCounter(offer: Offer, rules: BargainRules, now: Instant): Boolean {
    val statusAllowed = offer.status == OfferStatus.new || offer.status == OfferStatus.countered
    val countersAvailable = offer.countersUsed < rules.maxCounters
    val ttlActive = offer.expiresAt?.isAfter(now) ?: false
    return statusAllowed && countersAvailable && ttlActive
}

interface OrdersRepository {
    suspend fun create(order: Order)
    suspend fun get(id: String): Order?
    suspend fun listByUser(userId: Long): List<Order>
    suspend fun setStatus(id: String, status: OrderStatus)
    suspend fun setInvoiceMessage(id: String, invoiceMessageId: Int)
    suspend fun markPaid(id: String, provider: String, providerChargeId: String, telegramPaymentChargeId: String)
    suspend fun listPendingOlderThan(cutoff: Instant): List<Order>
}

interface OrderStatusHistoryRepository {
    suspend fun append(entry: OrderStatusEntry): Long
    suspend fun list(orderId: String, limit: Int? = null): List<OrderStatusEntry>
}

interface WatchlistRepository {
    suspend fun add(entry: WatchEntry): Long
    suspend fun listByUser(userId: Long): List<WatchEntry>
}

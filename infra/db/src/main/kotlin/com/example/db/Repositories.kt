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
    suspend fun setStatus(id: String, status: OfferStatus, countersUsed: Int, lastCounterAmount: Long?)
}

interface OrdersRepository {
    suspend fun create(order: Order)
    suspend fun get(id: String): Order?
    suspend fun setStatus(id: String, status: OrderStatus)
    suspend fun setInvoiceMessage(id: String, invoiceMessageId: Int)
    suspend fun markPaid(id: String, provider: String, providerChargeId: String, telegramPaymentChargeId: String)
}

interface OrderStatusHistoryRepository {
    suspend fun append(entry: OrderStatusEntry): Long
    suspend fun list(orderId: String): List<OrderStatusEntry>
}

interface WatchlistRepository {
    suspend fun add(entry: WatchEntry): Long
    suspend fun listByUser(userId: Long): List<WatchEntry>
}

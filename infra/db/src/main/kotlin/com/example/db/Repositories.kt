package com.example.db

import com.example.domain.BargainRules
import com.example.domain.ChannelBinding
import com.example.domain.Cart
import com.example.domain.CartItem
import com.example.domain.Item
import com.example.domain.ItemMedia
import com.example.domain.ItemStatus
import com.example.domain.LinkContext
import com.example.domain.Merchant
import com.example.domain.Offer
import com.example.domain.OfferStatus
import com.example.domain.Order
import com.example.domain.OrderAttachment
import com.example.domain.OrderLine
import com.example.domain.OrderPaymentClaim
import com.example.domain.OrderPaymentDetails
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.MerchantPaymentMethod
import com.example.domain.Post
import com.example.domain.PricesDisplay
import com.example.domain.Storefront
import com.example.domain.Variant
import com.example.domain.PaymentClaimStatus
import com.example.domain.PaymentMethodType
import com.example.domain.OrderAttachmentKind
import java.time.Instant

interface MerchantsRepository {
    suspend fun getById(id: String): Merchant?
}

interface StorefrontsRepository {
    suspend fun create(storefront: Storefront)
    suspend fun getById(id: String): Storefront?
    suspend fun listByMerchant(merchantId: String): List<Storefront>
}

interface ChannelBindingsRepository {
    suspend fun bind(storefrontId: String, channelId: Long, createdAt: Instant): Long
    suspend fun getByChannel(channelId: Long): ChannelBinding?
    suspend fun listByStorefront(storefrontId: String): List<ChannelBinding>
}

interface LinkContextsRepository {
    suspend fun create(context: LinkContext): Long
    suspend fun getByTokenHash(tokenHash: String): LinkContext?
    suspend fun revokeByTokenHash(tokenHash: String, revokedAt: Instant): Boolean
}

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
    suspend fun setStock(variantId: String, stock: Int): StockChange?
    suspend fun getById(id: String): Variant?
    suspend fun decrementStock(variantId: String, qty: Int): Boolean
    suspend fun decrementStockBatch(variantQty: Map<String, Int>): Boolean
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
    suspend fun setPaymentClaimed(orderId: String, claimedAt: Instant): Boolean
    suspend fun clearPaymentClaimedAt(orderId: String): Boolean
    suspend fun setPaymentMethodSelection(orderId: String, type: PaymentMethodType, selectedAt: Instant): Boolean
    suspend fun listPendingClaimOlderThan(cutoff: Instant): List<Order>
    suspend fun listPendingReviewOlderThan(cutoff: Instant): List<Order>
    suspend fun listPendingOlderThan(cutoff: Instant): List<Order>
}

interface OrderLinesRepository {
    suspend fun createBatch(lines: List<OrderLine>)
    suspend fun listByOrder(orderId: String): List<OrderLine>
    suspend fun listByOrders(orderIds: List<String>): Map<String, List<OrderLine>>
}

interface OrderStatusHistoryRepository {
    suspend fun append(entry: OrderStatusEntry): Long
    suspend fun list(orderId: String, limit: Int? = null): List<OrderStatusEntry>
}

interface MerchantPaymentMethodsRepository {
    suspend fun getEnabledMethod(merchantId: String, type: PaymentMethodType): MerchantPaymentMethod?
    suspend fun getMethod(merchantId: String, type: PaymentMethodType): MerchantPaymentMethod?
}

interface OrderPaymentDetailsRepository {
    suspend fun getByOrder(orderId: String): OrderPaymentDetails?
    suspend fun upsert(details: OrderPaymentDetails)
}

interface OrderPaymentClaimsRepository {
    suspend fun getSubmittedByOrder(orderId: String): OrderPaymentClaim?
    suspend fun insertClaim(claim: OrderPaymentClaim): Long
    suspend fun setStatus(id: Long, status: PaymentClaimStatus, comment: String?)
}

interface OrderAttachmentsRepository {
    suspend fun create(attachment: OrderAttachment): Long
    suspend fun getById(id: Long): OrderAttachment?
    suspend fun listByOrder(orderId: String): List<OrderAttachment>
    suspend fun listByOrderAndKind(orderId: String, kind: OrderAttachmentKind): List<OrderAttachment>
}

data class CartItemWithCart(
    val item: CartItem,
    val cart: Cart
)

interface CartsRepository {
    suspend fun getByMerchantAndBuyer(merchantId: String, buyerUserId: Long): Cart?
    suspend fun getOrCreate(merchantId: String, buyerUserId: Long, now: Instant): Cart
    suspend fun touch(cartId: Long, now: Instant)
}

interface CartItemsRepository {
    suspend fun listByCart(cartId: Long): List<CartItem>
    suspend fun getById(id: Long): CartItem?
    suspend fun create(item: CartItem): Long
    suspend fun updateQty(lineId: Long, qty: Int)
    suspend fun updateVariant(lineId: Long, variantId: String?, priceSnapshotMinor: Long, currency: String)
    suspend fun delete(lineId: Long): Boolean
    suspend fun getLineWithCart(lineId: Long): CartItemWithCart?
}

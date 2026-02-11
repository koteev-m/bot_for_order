package com.example.db

import com.example.domain.AdminUser
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
import com.example.domain.AuditLogEntry
import com.example.domain.EventLogEntry
import com.example.domain.IdempotencyKeyRecord
import com.example.domain.MerchantPaymentMethod
import com.example.domain.MerchantDeliveryMethod
import com.example.domain.Post
import com.example.domain.PricesDisplay
import com.example.domain.Storefront
import com.example.domain.Variant
import com.example.domain.PaymentClaimStatus
import com.example.domain.PaymentMethodType
import com.example.domain.OrderAttachmentKind
import com.example.domain.OrderDelivery
import com.example.domain.OutboxMessage
import com.example.domain.BuyerDeliveryProfile
import com.example.domain.DeliveryMethodType
import java.time.Instant

interface MerchantsRepository {
    suspend fun getById(id: String): Merchant?
}

interface AdminUsersRepository {
    suspend fun get(merchantId: String, userId: Long): AdminUser?
    suspend fun upsert(user: AdminUser)
    suspend fun listByMerchant(merchantId: String): List<AdminUser>
}

interface StorefrontsRepository {
    suspend fun create(storefront: Storefront)
    suspend fun getById(id: String): Storefront?
    suspend fun listByMerchant(merchantId: String): List<Storefront>
    suspend fun upsert(storefront: Storefront)
}

interface ChannelBindingsRepository {
    suspend fun bind(storefrontId: String, channelId: Long, createdAt: Instant): Long
    suspend fun getByChannel(channelId: Long): ChannelBinding?
    suspend fun listByStorefront(storefrontId: String): List<ChannelBinding>
    suspend fun upsert(storefrontId: String, channelId: Long, createdAt: Instant): Long
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
    suspend fun listByMerchantAndStatus(
        merchantId: String,
        statuses: List<OrderStatus>,
        limit: Int,
        offset: Long
    ): List<Order>
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
    suspend fun upsert(method: MerchantPaymentMethod)
}

interface MerchantDeliveryMethodsRepository {
    suspend fun getEnabledMethod(merchantId: String, type: DeliveryMethodType): MerchantDeliveryMethod?
    suspend fun getMethod(merchantId: String, type: DeliveryMethodType): MerchantDeliveryMethod?
    suspend fun upsert(method: MerchantDeliveryMethod)
}

interface OrderPaymentDetailsRepository {
    suspend fun getByOrder(orderId: String): OrderPaymentDetails?
    suspend fun upsert(details: OrderPaymentDetails)
}

interface OrderPaymentClaimsRepository {
    suspend fun getSubmittedByOrder(orderId: String): OrderPaymentClaim?
    suspend fun getLatestByOrder(orderId: String): OrderPaymentClaim?
    suspend fun insertClaim(claim: OrderPaymentClaim): Long
    suspend fun setStatus(id: Long, status: PaymentClaimStatus, comment: String?)
}

interface OrderAttachmentsRepository {
    suspend fun create(attachment: OrderAttachment): Long
    suspend fun getById(id: Long): OrderAttachment?
    suspend fun listByOrder(orderId: String): List<OrderAttachment>
    suspend fun listByOrderAndKind(orderId: String, kind: OrderAttachmentKind): List<OrderAttachment>
    suspend fun listByClaimAndKind(claimId: Long, kind: OrderAttachmentKind): List<OrderAttachment>
}

interface OrderDeliveryRepository {
    suspend fun getByOrder(orderId: String): OrderDelivery?
    suspend fun upsert(delivery: OrderDelivery)
    suspend fun listByOrders(orderIds: List<String>): Map<String, OrderDelivery>
}

interface BuyerDeliveryProfileRepository {
    suspend fun get(merchantId: String, buyerUserId: Long): BuyerDeliveryProfile?
    suspend fun upsert(profile: BuyerDeliveryProfile)
}

interface AuditLogRepository {
    suspend fun insert(entry: AuditLogEntry): Long
}

interface EventLogRepository {
    suspend fun insert(entry: EventLogEntry): Long
}

/**
 * Idempotency storage for a given (merchantId, userId, scope, key) tuple.
 *
 * Time boundary (TTL):
 * - A record is valid if createdAt >= validAfter (inclusive).
 * - A record is expired if createdAt < validAfter (strict).
 *
 * Concurrency / invariants:
 * - tryInsert is atomic and must not overwrite an existing record.
 * - updateResponse must not change requestHash or createdAt.
 *
 * In-progress:
 * - A record may exist with null responseStatus/responseJson to indicate "in progress".
 *
 * Boolean semantics:
 * - Methods returning Boolean return true only if the action actually happened (inserted/deleted).
 */
interface IdempotencyRepository {
    /**
     * Returns the record only if createdAt >= validAfter (inclusive).
     */
    suspend fun findValid(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): IdempotencyKeyRecord?

    /**
     * Atomic insert that must not overwrite; returns true iff inserted.
     */
    suspend fun tryInsert(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        requestHash: String,
        createdAt: Instant
    ): Boolean

    /**
     * Updates response fields only; must not change requestHash or createdAt.
     */
    suspend fun updateResponse(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        responseStatus: Int,
        responseJson: String
    )

    /**
     * Deletes unconditionally.
     */
    suspend fun delete(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String
    )

    /**
     * Deletes only if createdAt < validAfter (strict); returns true iff deleted.
     */
    suspend fun deleteIfExpired(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): Boolean
}

interface TelegramWebhookDedupRepository {
    suspend fun tryAcquire(
        botType: String,
        updateId: Long,
        now: Instant,
        staleBefore: Instant
    ): TelegramWebhookDedupAcquireResult

    suspend fun markProcessed(botType: String, updateId: Long, processedAt: Instant)

    suspend fun releaseProcessing(botType: String, updateId: Long)

    suspend fun purge(processedBefore: Instant, staleProcessingBefore: Instant): Int
}

interface OutboxRepository {
    suspend fun insert(type: String, payloadJson: String, now: Instant): Long
    suspend fun fetchDueBatch(limit: Int, now: Instant, processingLeaseUntil: Instant): List<OutboxMessage>
    suspend fun markDone(id: Long, expectedAttempts: Int): Boolean
    suspend fun reschedule(id: Long, expectedAttempts: Int, nextAttemptAt: Instant, lastError: String): Boolean
    suspend fun markFailed(id: Long, expectedAttempts: Int, lastError: String): Boolean
    suspend fun countBacklog(now: Instant): Long
}

data class TelegramPublishAlbumState(
    val operationId: String,
    val itemId: String,
    val channelId: Long,
    val messageIdsJson: String?,
    val firstMessageId: Int?,
    val addToken: String?,
    val buyToken: String?,
    val postInserted: Boolean,
    val editEnqueued: Boolean,
    val pinEnqueued: Boolean
)

interface TelegramPublishAlbumStateRepository {
    suspend fun upsertOperation(operationId: String, itemId: String, channelId: Long, now: Instant)
    suspend fun getByOperationId(operationId: String): TelegramPublishAlbumState?
    suspend fun saveMessages(operationId: String, messageIdsJson: String, firstMessageId: Int, now: Instant)
    suspend fun saveAddToken(operationId: String, addToken: String, now: Instant)
    suspend fun saveBuyToken(operationId: String, buyToken: String, now: Instant)
    suspend fun markPostInserted(operationId: String, now: Instant)
    suspend fun markEditEnqueued(operationId: String, now: Instant)
    suspend fun markPinEnqueued(operationId: String, now: Instant)
}

enum class TelegramWebhookDedupAcquireResult {
    ACQUIRED,
    ALREADY_PROCESSED,
    IN_PROGRESS
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

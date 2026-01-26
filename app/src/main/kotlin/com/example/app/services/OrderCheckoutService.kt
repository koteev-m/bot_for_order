package com.example.app.services

import com.example.app.api.ApiError
import com.example.app.config.AppConfig
import com.example.db.CartsRepository
import com.example.db.CartItemsRepository
import com.example.db.DatabaseTx
import com.example.db.MerchantsRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrdersRepository
import com.example.db.VariantsRepository
import com.example.db.tables.CartItemsTable
import com.example.db.tables.CartsTable
import com.example.db.tables.OrderLinesTable
import com.example.db.tables.OrdersTable
import com.example.domain.Order
import com.example.domain.OrderLine
import com.example.domain.OrderStatus
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldRequest
import com.example.domain.hold.OrderHoldService
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.time.Instant
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

private const val ORDER_CREATE_LOCK_WAIT_MS = 500L
private const val ORDER_CREATE_LOCK_LEASE_MS = 10_000L

class OrderCheckoutService(
    private val config: AppConfig,
    private val dbTx: DatabaseTx,
    private val cartsRepository: CartsRepository,
    private val cartItemsRepository: CartItemsRepository,
    private val merchantsRepository: MerchantsRepository,
    private val variantsRepository: VariantsRepository,
    private val ordersRepository: OrdersRepository,
    private val orderLinesRepository: OrderLinesRepository,
    private val orderHoldService: OrderHoldService,
    private val lockManager: LockManager,
    private val orderDedupStore: OrderDedupStore
) {
    private val log = LoggerFactory.getLogger(OrderCheckoutService::class.java)

    suspend fun createFromCart(buyerUserId: Long, now: Instant = Instant.now()): OrderWithLines {
        val merchantId = config.merchants.defaultMerchantId
        val cart = cartsRepository.getByMerchantAndBuyer(merchantId, buyerUserId)
            ?: throw ApiError("cart_empty", HttpStatusCode.Conflict)
        val lockKey = "order:create:${cart.id}:${buyerUserId}"
        return lockManager.withLock(lockKey, ORDER_CREATE_LOCK_WAIT_MS, ORDER_CREATE_LOCK_LEASE_MS) {
            val dedupKey = buildDedupKey(buyerUserId, cart.id)
            val items = cartItemsRepository.listByCart(cart.id)
            val snapshotHash = if (items.isNotEmpty()) buildSnapshotHash(items) else null
            val dedupEntry = orderDedupStore.get(dedupKey)?.let { parseDedupValue(it) }
            if (dedupEntry != null) {
                val existingOrder = ordersRepository.get(dedupEntry.orderId)
                if (existingOrder != null) {
                    if (items.isEmpty() || dedupEntry.snapshotHash == snapshotHash) {
                        val existingLines = orderLinesRepository.listByOrder(existingOrder.id)
                        return@withLock OrderWithLines(existingOrder, existingLines)
                    }
                }
                runCatching { orderDedupStore.delete(dedupKey) }
                    .onFailure { error -> log.warn("order_dedup_cleanup_failed orderId={} reason={}", dedupEntry.orderId, error.message) }
            }

            if (items.isEmpty()) throw ApiError("cart_empty", HttpStatusCode.Conflict)

            val normalizedCurrencies = items.map { it.currency.uppercase() }.distinct()
            if (normalizedCurrencies.size != 1) {
                throw ApiError("invalid_cart_currency", HttpStatusCode.Conflict)
            }
            val currency = normalizedCurrencies.first()

            val variantGroups = items.filter { it.variantId != null }.groupBy { it.variantId!! }
            variantGroups.forEach { (variantId, lines) ->
                val totalQty = lines.sumOf { it.qty }
                val variant = variantsRepository.getById(variantId)
                    ?: throw ApiError("variant_not_available", HttpStatusCode.Conflict)
                if (!variant.active || variant.stock < totalQty) {
                    throw ApiError("variant_not_available", HttpStatusCode.Conflict)
                }
            }

            val orderId = generateOrderId(buyerUserId)
            val totalAmount = items.sumOf { it.qty.toLong() * it.priceSnapshotMinor }
            val singleLine = items.singleOrNull()
            val order = Order(
                id = orderId,
                merchantId = merchantId,
                userId = buyerUserId,
                itemId = singleLine?.listingId,
                variantId = singleLine?.variantId,
                qty = singleLine?.qty,
                currency = currency,
                amountMinor = totalAmount,
                deliveryOption = null,
                addressJson = null,
                provider = null,
                providerChargeId = null,
                telegramPaymentChargeId = null,
                invoiceMessageId = null,
                status = OrderStatus.pending,
                createdAt = now,
                updatedAt = now
            )

            val lines = items.map { line ->
                OrderLine(
                    orderId = orderId,
                    listingId = line.listingId,
                    variantId = line.variantId,
                    qty = line.qty,
                    priceSnapshotMinor = line.priceSnapshotMinor,
                    currency = currency,
                    sourceStorefrontId = line.sourceStorefrontId,
                    sourceChannelId = line.sourceChannelId,
                    sourcePostMessageId = line.sourcePostMessageId
                )
            }

            val holdRequests = items
                .groupBy { it.variantId ?: it.listingId }
                .values
                .map { group ->
                    val first = group.first()
                    OrderHoldRequest(
                        listingId = first.listingId,
                        variantId = first.variantId,
                        qty = group.sumOf { it.qty }
                    )
                }

            val merchant = merchantsRepository.getById(merchantId)
                ?: throw ApiError("merchant_not_found", HttpStatusCode.NotFound)
            val claimTtlSec = merchant.paymentClaimWindowSeconds.toLong()
            if (claimTtlSec <= 0) throw ApiError("hold_unavailable", HttpStatusCode.Conflict)

            val holdsAcquired = orderHoldService.tryAcquire(orderId, holdRequests, claimTtlSec)
            if (!holdsAcquired) throw ApiError("hold_conflict", HttpStatusCode.Conflict)

            try {
                dbTx.tx {
                    OrdersTable.insert {
                        it[OrdersTable.id] = order.id
                        it[OrdersTable.merchantId] = order.merchantId
                        it[OrdersTable.userId] = order.userId
                        it[OrdersTable.itemId] = order.itemId
                        it[OrdersTable.variantId] = order.variantId
                        it[OrdersTable.qty] = order.qty
                        it[OrdersTable.currency] = order.currency
                        it[OrdersTable.amountMinor] = order.amountMinor
                        it[OrdersTable.deliveryOption] = order.deliveryOption
                        it[OrdersTable.addressJson] = order.addressJson
                        it[OrdersTable.provider] = order.provider
                        it[OrdersTable.providerChargeId] = order.providerChargeId
                        it[OrdersTable.telegramPaymentChargeId] = order.telegramPaymentChargeId
                        it[OrdersTable.invoiceMessageId] = order.invoiceMessageId
                        it[OrdersTable.status] = order.status.name
                        it[OrdersTable.paymentClaimedAt] = order.paymentClaimedAt
                        it[OrdersTable.paymentDecidedAt] = order.paymentDecidedAt
                        it[OrdersTable.paymentMethodType] = order.paymentMethodType?.name
                        it[OrdersTable.paymentMethodSelectedAt] = order.paymentMethodSelectedAt
                        it[OrdersTable.createdAt] = order.createdAt
                        it[OrdersTable.updatedAt] = order.updatedAt
                    }
                    OrderLinesTable.batchInsert(lines) { line ->
                        this[OrderLinesTable.orderId] = line.orderId
                        this[OrderLinesTable.listingId] = line.listingId
                        this[OrderLinesTable.variantId] = line.variantId
                        this[OrderLinesTable.qty] = line.qty
                        this[OrderLinesTable.priceSnapshotMinor] = line.priceSnapshotMinor
                        this[OrderLinesTable.currency] = line.currency
                        this[OrderLinesTable.sourceStorefrontId] = line.sourceStorefrontId
                        this[OrderLinesTable.sourceChannelId] = line.sourceChannelId
                        this[OrderLinesTable.sourcePostMessageId] = line.sourcePostMessageId
                    }
                    CartItemsTable.deleteWhere { CartItemsTable.cartId eq cart.id }
                    CartsTable.update({ CartsTable.id eq cart.id }) {
                        it[updatedAt] = CurrentTimestamp()
                    }
                }
            } catch (error: Exception) {
                orderHoldService.release(orderId, holdRequests)
                throw error
            }

            val dedupValue = buildDedupValue(orderId, snapshotHash!!)
            runCatching {
                orderDedupStore.set(dedupKey, dedupValue, claimTtlSec.coerceAtLeast(1).toInt())
            }.onFailure { error ->
                log.warn("order_dedup_set_failed orderId={} reason={}", orderId, error.message)
            }
            OrderWithLines(order, lines)
        }
    }

    private fun buildDedupKey(userId: Long, cartId: Long): String {
        return "order:dedup:$userId:$cartId"
    }

    private fun buildDedupValue(orderId: String, snapshotHash: String): String {
        return "$orderId:$snapshotHash"
    }

    private fun parseDedupValue(value: String): DedupEntry? {
        val parts = value.split(":", limit = 2)
        if (parts.isEmpty()) return null
        val orderId = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val snapshot = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return DedupEntry(orderId, snapshot)
    }

    private fun buildSnapshotHash(items: List<com.example.domain.CartItem>): String {
        val normalized = items
            .map { item ->
                listOf(
                    item.listingId,
                    item.variantId ?: "",
                    item.qty.toString(),
                    item.priceSnapshotMinor.toString(),
                    item.currency.uppercase(),
                    item.sourceStorefrontId ?: "",
                    item.sourceChannelId?.toString() ?: "",
                    item.sourcePostMessageId?.toString() ?: ""
                ).joinToString("|")
            }
            .sorted()
            .joinToString(";")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun generateOrderId(userId: Long): String = "ord_${System.currentTimeMillis()}_$userId"

    private data class DedupEntry(
        val orderId: String,
        val snapshotHash: String?
    )
}

data class OrderWithLines(
    val order: Order,
    val lines: List<OrderLine>
)

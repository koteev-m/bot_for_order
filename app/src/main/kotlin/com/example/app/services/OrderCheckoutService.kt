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
import java.time.Instant
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update

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
    suspend fun createFromCart(buyerUserId: Long, now: Instant = Instant.now()): OrderWithLines {
        val merchantId = config.merchants.defaultMerchantId
        val cart = cartsRepository.getByMerchantAndBuyer(merchantId, buyerUserId)
            ?: throw ApiError("cart_empty", HttpStatusCode.Conflict)
        val lockKey = "order:create:${cart.id}:${buyerUserId}"
        return lockManager.withLock(lockKey, ORDER_CREATE_LOCK_WAIT_MS, ORDER_CREATE_LOCK_LEASE_MS) {
            val dedupKey = buildDedupKey(buyerUserId, cart.id, cart.updatedAt)
            val existingOrderId = orderDedupStore.get(dedupKey)
            if (existingOrderId != null) {
                val existingOrder = ordersRepository.get(existingOrderId)
                if (existingOrder != null) {
                    val existingLines = orderLinesRepository.listByOrder(existingOrderId)
                    return@withLock OrderWithLines(existingOrder, existingLines)
                }
                orderDedupStore.delete(dedupKey)
            }

            val items = cartItemsRepository.listByCart(cart.id)
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
                        it[id] = order.id
                        it[merchantId] = order.merchantId
                        it[userId] = order.userId
                        it[itemId] = order.itemId
                        it[variantId] = order.variantId
                        it[qty] = order.qty
                        it[currency] = order.currency
                        it[amountMinor] = order.amountMinor
                        it[deliveryOption] = order.deliveryOption
                        it[addressJson] = order.addressJson
                        it[provider] = order.provider
                        it[providerChargeId] = order.providerChargeId
                        it[telegramPaymentChargeId] = order.telegramPaymentChargeId
                        it[invoiceMessageId] = order.invoiceMessageId
                        it[status] = order.status.name
                        it[paymentClaimedAt] = order.paymentClaimedAt
                        it[paymentDecidedAt] = order.paymentDecidedAt
                        it[createdAt] = order.createdAt
                        it[updatedAt] = order.updatedAt
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

            orderDedupStore.set(dedupKey, orderId, config.cart.addDedupWindowSec.coerceAtLeast(1))
            OrderWithLines(order, lines)
        }
    }

    private fun buildDedupKey(userId: Long, cartId: Long, cartUpdatedAt: Instant): String {
        return "order:dedup:$userId:$cartId:${cartUpdatedAt.toEpochMilli()}"
    }

    private fun generateOrderId(userId: Long): String = "ord_${System.currentTimeMillis()}_$userId"
}

data class OrderWithLines(
    val order: Order,
    val lines: List<OrderLine>
)

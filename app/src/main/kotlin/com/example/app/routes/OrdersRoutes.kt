package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.OrderCard
import com.example.app.api.OrderCreateRequest
import com.example.app.api.OrderCreateResponse
import com.example.app.api.OrderHistoryEntry
import com.example.app.api.OrdersPage
import com.example.app.config.AppConfig
import com.example.app.security.requireUserId
import com.example.app.services.PaymentsService
import com.example.db.ItemsRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.VariantsRepository
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.ReserveSource
import com.example.domain.hold.ReserveWriteResult
import com.example.domain.hold.StockReservePayload
import java.time.Instant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private const val ORDER_HISTORY_LIMIT = 3
private const val ORDER_STOCK_LOCK_WAIT_MS = 500L
private val ordersLog = LoggerFactory.getLogger("OrdersRoutes")

data class OrderRoutesDeps(
    val itemsRepository: ItemsRepository,
    val variantsRepository: VariantsRepository,
    val ordersRepository: OrdersRepository,
    val historyRepository: OrderStatusHistoryRepository,
    val paymentsService: PaymentsService,
    val holdService: HoldService,
    val lockManager: LockManager
)

private data class OrderCreationDeps(
    val config: AppConfig,
    val routesDeps: OrderRoutesDeps
)

fun Route.registerOrdersRoutes(
    cfg: AppConfig,
    routesDeps: OrderRoutesDeps
) {
    val deps = OrderCreationDeps(cfg, routesDeps)
    post("/orders") {
        handleCreateOrder(call, deps)
    }
    get("/orders/me") {
        handleOrdersMe(call, routesDeps.ordersRepository, routesDeps.historyRepository)
    }
}

private suspend fun handleCreateOrder(
    call: ApplicationCall,
    deps: OrderCreationDeps
) {
    val userId = call.requireUserId()
    val req = call.receive<OrderCreateRequest>()

    val item = validateOrderRequest(req, deps.config, deps.routesDeps.itemsRepository)
    req.variantId?.let { variantId ->
        val variant = findVariantForItem(variantId, req.itemId, deps.routesDeps.variantsRepository)
        ensure(variant.active && variant.stock > 0) { "variant not available" }
        ensure(req.qty <= variant.stock) { "qty exceeds stock" }
    }

    val orderId = "ord_${'$'}{System.currentTimeMillis()}_${'$'}userId"

    val order = Order(
        id = orderId,
        merchantId = item.merchantId,
        userId = userId,
        itemId = req.itemId,
        variantId = req.variantId,
        qty = req.qty,
        currency = req.currency.uppercase(),
        amountMinor = req.amountMinor,
        deliveryOption = req.deliveryOption,
        addressJson = req.addressJson,
        provider = null,
        providerChargeId = null,
        telegramPaymentChargeId = null,
        invoiceMessageId = null,
        status = OrderStatus.pending,
        updatedAt = Instant.now()
    )
    deps.routesDeps.ordersRepository.create(order)
    val reserveTtl = deps.config.server.orderReserveTtlSec.toLong()
    val reserveResult = try {
        ensureDirectOrderReserve(order, reserveTtl, deps)
    } catch (error: StockGuardException) {
        ordersLog.warn(
            "order_reserve_failed orderId={} item={} variant={}",
            orderId,
            order.itemId,
            order.variantId,
            error
        )
        deps.routesDeps.ordersRepository.setStatus(orderId, OrderStatus.canceled)
        throw ApiError("variant not available", HttpStatusCode.BadRequest)
    }
    if (reserveResult == ReserveWriteResult.REFRESHED) {
        ordersLog.info("order_reserve_refreshed orderId={} item={} variant={}", orderId, order.itemId, order.variantId)
        call.respond(HttpStatusCode.Accepted, OrderCreateResponse(orderId = orderId, status = order.status.name))
        return
    }

    deps.routesDeps.paymentsService.createAndSendInvoice(order, item.title, photoUrl = null)

    call.respond(HttpStatusCode.Accepted, OrderCreateResponse(orderId = orderId, status = order.status.name))
}

private suspend fun validateOrderRequest(
    req: OrderCreateRequest,
    cfg: AppConfig,
    itemsRepo: ItemsRepository
): Item {
    ensure(req.itemId.isNotBlank()) { "itemId required" }
    ensure(req.qty > 0) { "qty must be > 0" }
    ensure(req.amountMinor > 0) { "amountMinor must be > 0" }

    val expectedCurrency = cfg.payments.invoiceCurrency.uppercase()
    ensure(req.currency.uppercase() == expectedCurrency) {
        "currency must be ${cfg.payments.invoiceCurrency}"
    }

    val item = itemsRepo.getById(req.itemId) ?: throw ApiError("item not found", HttpStatusCode.NotFound)
    ensure(item.status == ItemStatus.active) { "item is not active" }
    return item
}

private suspend fun handleOrdersMe(
    call: ApplicationCall,
    ordersRepo: OrdersRepository,
    historyRepo: OrderStatusHistoryRepository
) {
    val userId = call.requireUserId()
    val orders = ordersRepo.listByUser(userId)
    val cards = orders.map { order ->
        val history = historyRepo.list(order.id, ORDER_HISTORY_LIMIT)
            .sortedBy { it.ts }
            .map { entry ->
                OrderHistoryEntry(
                    status = entry.status.name,
                    comment = entry.comment,
                    ts = entry.ts.toString()
                )
            }
        OrderCard(
            orderId = order.id,
            itemId = order.itemId,
            variantId = order.variantId,
            qty = order.qty,
            currency = order.currency,
            amountMinor = order.amountMinor,
            status = order.status.name,
            updatedAt = order.updatedAt.toString(),
            history = history
        )
    }
    call.respond(OrdersPage(items = cards))
}

private suspend fun ensureDirectOrderReserve(
    order: Order,
    ttlSec: Long,
    deps: OrderCreationDeps
): ReserveWriteResult? {
    if (ttlSec <= 0) return null
    val payload = StockReservePayload(
        itemId = order.itemId,
        variantId = order.variantId,
        qty = order.qty,
        userId = order.userId,
        ttlSec = ttlSec,
        from = ReserveSource.ORDER
    )
    return withStockLock(
        deps.routesDeps.lockManager,
        order.itemId,
        order.variantId,
        deps.config.server.reserveStockLockSec
    ) {
        validateVariantStock(order.itemId, order.variantId, order.qty, deps.routesDeps.variantsRepository)
        val result = deps.routesDeps.holdService.createOrderReserve(order.id, payload, ttlSec)
        ordersLog.info(
            "order_reserve_set orderId={} item={} variant={} qty={} ttl={}s refreshed={}",
            order.id,
            order.itemId,
            order.variantId,
            order.qty,
            ttlSec,
            result == ReserveWriteResult.REFRESHED
        )
        result
    }
}

private suspend fun <T> withStockLock(
    lockManager: LockManager,
    itemId: String,
    variantId: String?,
    leaseSec: Int,
    block: suspend () -> T
): T {
    val leaseMs = leaseSec.coerceAtLeast(1) * 1_000L
    val key = buildStockLockKey(itemId, variantId)
    return lockManager.withLock(key, ORDER_STOCK_LOCK_WAIT_MS, leaseMs, block)
}

private suspend fun validateVariantStock(
    itemId: String,
    variantId: String?,
    qty: Int,
    variantsRepository: VariantsRepository
) {
    if (variantId == null) return
    val variant = variantsRepository.getById(variantId)
        ?: throw StockGuardException("variant missing")
    check(variant.itemId == itemId) { "variant mismatch" }
    if (!variant.active || variant.stock < qty) {
        throw StockGuardException("variant not available")
    }
}

private fun buildStockLockKey(itemId: String, variantId: String?): String {
    val variantKey = variantId ?: "_"
    return "stock:$itemId:$variantKey"
}

private class StockGuardException(message: String) : IllegalStateException(message)

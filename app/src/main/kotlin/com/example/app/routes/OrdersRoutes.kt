package com.example.app.routes

import com.example.app.api.OrderCard
import com.example.app.api.OrderCreateResponse
import com.example.app.api.OrderHistoryEntry
import com.example.app.api.OrdersPage
import com.example.app.api.OrderLineDto
import com.example.app.security.requireUserId
import com.example.app.services.OrderCheckoutService
import com.example.app.services.PaymentsService
import com.example.db.ItemsRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private const val ORDER_HISTORY_LIMIT = 3

data class OrderRoutesDeps(
    val itemsRepository: ItemsRepository,
    val ordersRepository: OrdersRepository,
    val orderLinesRepository: OrderLinesRepository,
    val historyRepository: OrderStatusHistoryRepository,
    val paymentsService: PaymentsService,
    val orderCheckoutService: OrderCheckoutService
)

private data class OrderCreationDeps(
    val routesDeps: OrderRoutesDeps
)

fun Route.registerOrdersRoutes(
    routesDeps: OrderRoutesDeps
) {
    val deps = OrderCreationDeps(routesDeps)
    post("/orders") {
        handleCreateOrder(call, deps)
    }
    get("/orders/me") {
        handleOrdersMe(call, routesDeps.ordersRepository, routesDeps.orderLinesRepository, routesDeps.historyRepository)
    }
}

private suspend fun handleCreateOrder(
    call: ApplicationCall,
    deps: OrderCreationDeps
) {
    val userId = call.requireUserId()
    val result = deps.routesDeps.orderCheckoutService.createFromCart(userId)
    val order = result.order
    val lineForTitle = result.lines.firstOrNull()
    val itemTitle = lineForTitle?.let { line ->
        deps.routesDeps.itemsRepository.getById(line.listingId)?.title
    } ?: "Order"
    deps.routesDeps.paymentsService.createAndSendInvoice(order, itemTitle, photoUrl = null)

    call.respond(HttpStatusCode.Accepted, OrderCreateResponse(orderId = order.id, status = order.status.name))
}

private suspend fun handleOrdersMe(
    call: ApplicationCall,
    ordersRepo: OrdersRepository,
    orderLinesRepo: OrderLinesRepository,
    historyRepo: OrderStatusHistoryRepository
) {
    val userId = call.requireUserId()
    val orders = ordersRepo.listByUser(userId)
    val linesByOrder = orderLinesRepo.listByOrders(orders.map { it.id })
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
        val lines = linesByOrder[order.id].orEmpty()
        val legacyLine = lines.singleOrNull()
        OrderCard(
            orderId = order.id,
            itemId = order.itemId ?: legacyLine?.listingId,
            variantId = order.variantId ?: legacyLine?.variantId,
            qty = order.qty ?: legacyLine?.qty,
            currency = order.currency,
            amountMinor = order.amountMinor,
            status = order.status.name,
            updatedAt = order.updatedAt.toString(),
            lines = lines.map { line ->
                OrderLineDto(
                    listingId = line.listingId,
                    variantId = line.variantId,
                    qty = line.qty,
                    priceSnapshotMinor = line.priceSnapshotMinor,
                    currency = line.currency,
                    sourceStorefrontId = line.sourceStorefrontId,
                    sourceChannelId = line.sourceChannelId,
                    sourcePostMessageId = line.sourcePostMessageId
                )
            },
            history = history
        )
    }
    call.respond(OrdersPage(items = cards))
}

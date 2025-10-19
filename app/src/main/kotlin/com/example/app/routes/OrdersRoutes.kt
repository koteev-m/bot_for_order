package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.OrderCreateRequest
import com.example.app.api.OrderCreateResponse
import com.example.app.api.OrdersPage
import com.example.app.api.requireUserId
import com.example.app.config.AppConfig
import com.example.db.ItemsRepository
import com.example.db.OrdersRepository
import com.example.db.VariantsRepository
import com.example.domain.ItemStatus
import com.example.domain.Order
import com.example.domain.OrderStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.registerOrdersRoutes(
    cfg: AppConfig,
    itemsRepo: ItemsRepository,
    variantsRepo: VariantsRepository,
    ordersRepo: OrdersRepository
) {
    post("/orders") {
        handleCreateOrder(call, cfg, itemsRepo, variantsRepo, ordersRepo)
    }
    get("/orders/me") {
        handleOrdersMe(call)
    }
}

private suspend fun handleCreateOrder(
    call: ApplicationCall,
    cfg: AppConfig,
    itemsRepo: ItemsRepository,
    variantsRepo: VariantsRepository,
    ordersRepo: OrdersRepository
) {
    val userId = call.requireUserId()
    val req = call.receive<OrderCreateRequest>()

    validateOrderRequest(req, cfg, itemsRepo)
    req.variantId?.let { variantId ->
        val variant = findVariantForItem(variantId, req.itemId, variantsRepo)
        ensure(variant.active && variant.stock > 0) { "variant not available" }
        ensure(req.qty <= variant.stock) { "qty exceeds stock" }
    }

    val orderId = "ord_${'$'}{System.currentTimeMillis()}_${'$'}userId"

    val order = Order(
        id = orderId,
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
        status = OrderStatus.pending
    )
    ordersRepo.create(order)

    call.respond(HttpStatusCode.Accepted, OrderCreateResponse(orderId = orderId, status = order.status.name))
}

private suspend fun validateOrderRequest(
    req: OrderCreateRequest,
    cfg: AppConfig,
    itemsRepo: ItemsRepository
) {
    ensure(req.itemId.isNotBlank()) { "itemId required" }
    ensure(req.qty > 0) { "qty must be > 0" }
    ensure(req.amountMinor > 0) { "amountMinor must be > 0" }

    val expectedCurrency = cfg.payments.invoiceCurrency.uppercase()
    ensure(req.currency.uppercase() == expectedCurrency) {
        "currency must be ${cfg.payments.invoiceCurrency}"
    }

    val item = itemsRepo.getById(req.itemId) ?: throw ApiError("item not found", HttpStatusCode.NotFound)
    ensure(item.status == ItemStatus.active) { "item is not active" }
}

private suspend fun handleOrdersMe(call: ApplicationCall) {
    call.requireUserId()
    call.respond(OrdersPage(items = emptyList()))
}

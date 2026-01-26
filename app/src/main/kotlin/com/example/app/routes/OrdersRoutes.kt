package com.example.app.routes

import com.example.app.api.OrderCard
import com.example.app.api.OrderCreateResponse
import com.example.app.api.OrderHistoryEntry
import com.example.app.api.OrdersPage
import com.example.app.api.OrderLineDto
import com.example.app.api.PaymentClaimRequest
import com.example.app.api.PaymentClaimResponse
import com.example.app.api.PaymentInstructionsResponse
import com.example.app.api.PaymentSelectRequest
import com.example.app.api.PaymentSelectResponse
import com.example.app.security.requireUserId
import com.example.app.services.OrderCheckoutService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.PaymentClaimAttachment
import com.example.app.services.PaymentsService
import com.example.db.ItemsRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.domain.PaymentMethodType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining

private const val ORDER_HISTORY_LIMIT = 3
private const val MAX_FORM_FIELD_BYTES = 20_000
private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

data class OrderRoutesDeps(
    val itemsRepository: ItemsRepository,
    val ordersRepository: OrdersRepository,
    val orderLinesRepository: OrderLinesRepository,
    val historyRepository: OrderStatusHistoryRepository,
    val paymentsService: PaymentsService,
    val orderCheckoutService: OrderCheckoutService,
    val manualPaymentsService: ManualPaymentsService
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
    post("/orders/{id}/payment/select") {
        handleSelectPayment(call, routesDeps.manualPaymentsService)
    }
    get("/orders/{id}/payment/instructions") {
        handlePaymentInstructions(call, routesDeps.manualPaymentsService)
    }
    post("/orders/{id}/payment/claim") {
        handlePaymentClaim(call, routesDeps.manualPaymentsService)
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

private suspend fun handleSelectPayment(
    call: ApplicationCall,
    manualPaymentsService: ManualPaymentsService
) {
    val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
    val userId = call.requireUserId()
    val request = call.receive<PaymentSelectRequest>()
    val methodType = runCatching { PaymentMethodType.valueOf(request.methodType.trim().uppercase()) }
        .getOrElse { throw com.example.app.api.ApiError("invalid_payment_method") }
    val order = manualPaymentsService.selectPaymentMethod(orderId, userId, methodType)
    call.respond(PaymentSelectResponse(orderId = order.id, status = order.status.name))
}

private suspend fun handlePaymentInstructions(
    call: ApplicationCall,
    manualPaymentsService: ManualPaymentsService
) {
    val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
    val userId = call.requireUserId()
    val instructions = manualPaymentsService.getPaymentInstructions(orderId, userId)
    call.respond(
        PaymentInstructionsResponse(
            methodType = instructions.methodType.name,
            mode = instructions.mode.name,
            text = instructions.text
        )
    )
}

private suspend fun handlePaymentClaim(
    call: ApplicationCall,
    manualPaymentsService: ManualPaymentsService
) {
    val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
    val userId = call.requireUserId()
    val (payload, attachments) = receiveClaimPayload(call)
    val claim = manualPaymentsService.submitClaim(
        orderId = orderId,
        buyerId = userId,
        txid = payload.txid,
        comment = payload.comment,
        attachments = attachments
    )
    call.respond(PaymentClaimResponse(id = claim.id, status = claim.status.name, createdAt = claim.createdAt.toString()))
}

private suspend fun receiveClaimPayload(call: ApplicationCall): Pair<PaymentClaimRequest, List<PaymentClaimAttachment>> {
    val contentType = call.request.contentType()
    if (contentType.match(ContentType.MultiPart.FormData)) {
        return receiveClaimMultipart(call)
    }
    return call.receive<PaymentClaimRequest>() to emptyList()
}

private suspend fun receiveClaimMultipart(
    call: ApplicationCall
): Pair<PaymentClaimRequest, List<PaymentClaimAttachment>> {
    val multipart = call.receiveMultipart()
    var txid: String? = null
    var comment: String? = null
    val attachments = mutableListOf<PaymentClaimAttachment>()

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> {
                val value = part.value
                if (value.length > MAX_FORM_FIELD_BYTES) {
                    throw com.example.app.api.ApiError("field_too_large")
                }
                when (part.name) {
                    "txid" -> txid = value
                    "comment" -> comment = value
                }
            }
            is PartData.FileItem -> {
                if (part.name != "attachments") {
                    part.dispose()
                    return@forEachPart
                }
                val contentType = part.contentType
                    ?.withoutParameters()
                    ?.toString()
                    ?.lowercase()
                    ?: "application/octet-stream"
                val bytes = readBytesWithLimit(part.provider(), MAX_ATTACHMENT_BYTES)
                attachments.add(
                    PaymentClaimAttachment(
                        filename = part.originalFileName,
                        contentType = contentType,
                        bytes = bytes
                    )
                )
            }
            else -> Unit
        }
        part.dispose()
    }
    return PaymentClaimRequest(txid = txid, comment = comment) to attachments
}

private suspend fun readBytesWithLimit(channel: ByteReadChannel, limit: Int): ByteArray {
    val packet = channel.readRemaining(limit.toLong() + 1)
    val bytes = packet.readBytes()
    if (bytes.size > limit) {
        throw com.example.app.api.ApiError("attachment_too_large")
    }
    return bytes
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

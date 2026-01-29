package com.example.app.routes

import com.example.app.api.OrderCard
import com.example.app.api.OrderCreateResponse
import com.example.app.api.OrderDeliveryRequest
import com.example.app.api.OrderDeliveryResponse
import com.example.app.api.OrderDeliverySummary
import com.example.app.api.OrderHistoryEntry
import com.example.app.api.OrdersPage
import com.example.app.api.OrderLineDto
import com.example.app.api.PaymentClaimRequest
import com.example.app.api.PaymentClaimResponse
import com.example.app.api.PaymentInstructionsResponse
import com.example.app.api.PaymentSelectRequest
import com.example.app.api.PaymentSelectResponse
import com.example.app.api.ApiError
import com.example.app.security.requireUserId
import com.example.app.services.DeliveryService
import com.example.app.services.DeliveryFieldsCodec
import com.example.app.services.IdempotencyService
import com.example.app.services.OrderCheckoutService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.PaymentClaimAttachment
import com.example.app.services.PaymentsService
import com.example.app.services.UserActionRateLimiter
import com.example.db.ItemsRepository
import com.example.db.OrderDeliveryRepository
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
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

private const val ORDER_HISTORY_LIMIT = 3
private const val MAX_FORM_FIELD_BYTES = 20_000
private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
private val IDEMPOTENCY_JSON = Json { encodeDefaults = false; explicitNulls = false }
private const val IDEMPOTENCY_SCOPE_ORDER_CREATE = "order_create"
private const val IDEMPOTENCY_SCOPE_PAYMENT_CLAIM = "payment_claim"

data class OrderRoutesDeps(
    val merchantId: String,
    val itemsRepository: ItemsRepository,
    val ordersRepository: OrdersRepository,
    val orderLinesRepository: OrderLinesRepository,
    val historyRepository: OrderStatusHistoryRepository,
    val paymentsService: PaymentsService,
    val orderCheckoutService: OrderCheckoutService,
    val manualPaymentsService: ManualPaymentsService,
    val orderDeliveryRepository: OrderDeliveryRepository,
    val deliveryService: DeliveryService,
    val idempotencyService: IdempotencyService,
    val userActionRateLimiter: UserActionRateLimiter
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
        handleOrdersMe(
            call,
            routesDeps.ordersRepository,
            routesDeps.orderLinesRepository,
            routesDeps.historyRepository,
            routesDeps.orderDeliveryRepository
        )
    }
    post("/orders/{id}/payment/select") {
        handleSelectPayment(call, routesDeps.manualPaymentsService)
    }
    get("/orders/{id}/payment/instructions") {
        handlePaymentInstructions(call, routesDeps.manualPaymentsService)
    }
    post("/orders/{id}/payment/claim") {
        handlePaymentClaim(call, routesDeps)
    }
    post("/orders/{id}/delivery") {
        handleOrderDelivery(call, routesDeps.deliveryService)
    }
}

private suspend fun handleCreateOrder(
    call: ApplicationCall,
    deps: OrderCreationDeps
) {
    val userId = call.requireUserId()
    val idempotencyKey = deps.routesDeps.idempotencyService.normalizeKey(
        call.request.headers["Idempotency-Key"]
    )
    if (idempotencyKey == null) {
        val result = deps.routesDeps.orderCheckoutService.createFromCart(userId)
        val order = result.order
        val lineForTitle = result.lines.firstOrNull()
        val itemTitle = lineForTitle?.let { line ->
            deps.routesDeps.itemsRepository.getById(line.listingId)?.title
        } ?: "Order"
        deps.routesDeps.paymentsService.createAndSendInvoice(order, itemTitle, photoUrl = null)

        call.respond(HttpStatusCode.Accepted, OrderCreateResponse(orderId = order.id, status = order.status.name))
        return
    }

    val requestHash = deps.routesDeps.idempotencyService.hashPayload("{}")
    val outcome = deps.routesDeps.idempotencyService.execute(
        merchantId = deps.routesDeps.merchantId,
        userId = userId,
        scope = IDEMPOTENCY_SCOPE_ORDER_CREATE,
        key = idempotencyKey,
        requestHash = requestHash
    ) {
        val result = deps.routesDeps.orderCheckoutService.createFromCart(userId)
        val order = result.order
        val lineForTitle = result.lines.firstOrNull()
        val itemTitle = lineForTitle?.let { line ->
            deps.routesDeps.itemsRepository.getById(line.listingId)?.title
        } ?: "Order"
        deps.routesDeps.paymentsService.createAndSendInvoice(order, itemTitle, photoUrl = null)
        val response = OrderCreateResponse(orderId = order.id, status = order.status.name)
        val responseJson = IDEMPOTENCY_JSON.encodeToString(response)
        IdempotencyService.IdempotentResponse(
            status = HttpStatusCode.Accepted,
            response = response,
            responseJson = responseJson
        )
    }
    when (outcome) {
        is IdempotencyService.IdempotentOutcome.Replay -> call.respondText(
            outcome.responseJson,
            ContentType.Application.Json,
            outcome.status
        )
        is IdempotencyService.IdempotentOutcome.Executed -> call.respond(outcome.status, outcome.response)
    }
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
    deps: OrderRoutesDeps
) {
    val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
    val userId = call.requireUserId()
    val (payload, attachments) = receiveClaimPayload(call)
    val idempotencyKey = deps.idempotencyService.normalizeKey(
        call.request.headers["Idempotency-Key"]
    )
    if (idempotencyKey == null) {
        if (!deps.userActionRateLimiter.allowClaim(userId)) {
            throw ApiError("rate_limited", HttpStatusCode.TooManyRequests)
        }
        val claim = deps.manualPaymentsService.submitClaim(
            orderId = orderId,
            buyerId = userId,
            txid = payload.txid,
            comment = payload.comment,
            attachments = attachments
        )
        call.respond(
            PaymentClaimResponse(id = claim.id, status = claim.status.name, createdAt = claim.createdAt.toString())
        )
        return
    }

    val requestHashPayload = buildClaimRequestHashPayload(orderId, payload, attachments)
    val requestHash = deps.idempotencyService.hashPayload(requestHashPayload)
    val outcome = deps.idempotencyService.execute(
        merchantId = deps.merchantId,
        userId = userId,
        scope = IDEMPOTENCY_SCOPE_PAYMENT_CLAIM,
        key = idempotencyKey,
        requestHash = requestHash
    ) {
        if (!deps.userActionRateLimiter.allowClaim(userId)) {
            throw ApiError("rate_limited", HttpStatusCode.TooManyRequests)
        }
        val claim = deps.manualPaymentsService.submitClaim(
            orderId = orderId,
            buyerId = userId,
            txid = payload.txid,
            comment = payload.comment,
            attachments = attachments
        )
        val response = PaymentClaimResponse(id = claim.id, status = claim.status.name, createdAt = claim.createdAt.toString())
        val responseJson = IDEMPOTENCY_JSON.encodeToString(response)
        IdempotencyService.IdempotentResponse(
            status = HttpStatusCode.OK,
            response = response,
            responseJson = responseJson
        )
    }
    when (outcome) {
        is IdempotencyService.IdempotentOutcome.Replay -> call.respondText(
            outcome.responseJson,
            ContentType.Application.Json,
            outcome.status
        )
        is IdempotencyService.IdempotentOutcome.Executed -> call.respond(outcome.status, outcome.response)
    }
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

private fun buildClaimRequestHashPayload(
    orderId: String,
    payload: PaymentClaimRequest,
    attachments: List<PaymentClaimAttachment>
): String {
    val json = buildJsonObject {
        put("orderId", orderId)
        put("txid", payload.txid?.trim().orEmpty())
        put("comment", payload.comment?.trim().orEmpty())
        put(
            "attachments",
            buildJsonArray {
                attachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("filename", attachment.filename?.trim().orEmpty())
                            put("contentType", attachment.contentType)
                            put("size", attachment.bytes.size)
                            put("sha256", sha256Hex(attachment.bytes))
                        }
                    )
                }
            }
        )
    }
    return IDEMPOTENCY_JSON.encodeToString(json)
}

private suspend fun readBytesWithLimit(channel: ByteReadChannel, limit: Int): ByteArray {
    val packet = channel.readRemaining(limit.toLong() + 1)
    val bytes = packet.readBytes()
    if (bytes.size > limit) {
        throw com.example.app.api.ApiError("attachment_too_large")
    }
    return bytes
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(bytes)
    return hash.joinToString("") { "%02x".format(it) }
}

private suspend fun handleOrdersMe(
    call: ApplicationCall,
    ordersRepo: OrdersRepository,
    orderLinesRepo: OrderLinesRepository,
    historyRepo: OrderStatusHistoryRepository,
    orderDeliveryRepository: OrderDeliveryRepository
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
        val delivery = orderDeliveryRepository.getByOrder(order.id)?.let { stored ->
            OrderDeliverySummary(
                type = stored.type.name,
                fields = DeliveryFieldsCodec.decodeFields(stored.fieldsJson)
            )
        }
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
            history = history,
            delivery = delivery
        )
    }
    call.respond(OrdersPage(items = cards))
}

private suspend fun handleOrderDelivery(
    call: ApplicationCall,
    deliveryService: DeliveryService
) {
    val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
    val userId = call.requireUserId()
    val request = call.receive<OrderDeliveryRequest>()
    val delivery = deliveryService.setOrderDelivery(orderId, userId, request.fields)
    call.respond(
        OrderDeliveryResponse(
            type = delivery.type.name,
            fields = DeliveryFieldsCodec.decodeFields(delivery.fieldsJson),
            createdAt = delivery.createdAt.toString(),
            updatedAt = delivery.updatedAt.toString()
        )
    )
}

package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.CartAddByTokenRequest
import com.example.app.api.CartAddResponse
import com.example.app.api.CartResponse
import com.example.app.api.CartUndoRequest
import com.example.app.api.CartVariantRequiredResponse
import com.example.app.config.AppConfig
import com.example.app.security.requireUserId
import com.example.app.services.CartAddResult
import com.example.app.services.CartService
import com.example.app.services.IdempotencyService
import com.example.app.services.QuickAddRequestValidation
import com.example.app.services.UserActionRateLimiter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun Route.registerCartRoutes(
    cartService: CartService,
    cfg: AppConfig,
    rateLimiter: UserActionRateLimiter,
    idempotencyService: IdempotencyService
) {
    post("/cart/add_by_token") {
        handleAddByToken(call, cartService, cfg, rateLimiter, idempotencyService)
    }
    post("/cart/update") {
        handleUpdate(call, cartService)
    }
    post("/cart/undo") {
        handleUndo(call, cartService)
    }
    get("/cart") {
        handleGetCart(call, cartService, cfg)
    }
}

private suspend fun handleAddByToken(
    call: ApplicationCall,
    cartService: CartService,
    cfg: AppConfig,
    rateLimiter: UserActionRateLimiter,
    idempotencyService: IdempotencyService
) {
    val buyerUserId = call.requireUserId()
    val req = call.receive<CartAddByTokenRequest>()
    val token = QuickAddRequestValidation.normalizeToken(req.token)
    QuickAddRequestValidation.validateVariantId(req.selectedVariantId)

    val request = req.copy(token = token)
    val idempotencyKey = idempotencyService.normalizeKey(call.request.headers["Idempotency-Key"])
    if (idempotencyKey == null) {
        if (!rateLimiter.allowAdd(buyerUserId)) {
            throw ApiError("rate_limited", HttpStatusCode.TooManyRequests)
        }
        call.respond(buildAddByTokenResponse(cartService, buyerUserId, request))
        return
    }

    val requestHash = idempotencyService.hashPayload(CART_IDEMPOTENCY_JSON.encodeToString(request))
    val outcome = idempotencyService.execute(
        merchantId = cfg.merchants.defaultMerchantId,
        userId = buyerUserId,
        scope = IDEMPOTENCY_SCOPE_CART_ADD_BY_TOKEN,
        key = idempotencyKey,
        requestHash = requestHash
    ) {
        if (!rateLimiter.allowAdd(buyerUserId)) {
            throw ApiError("rate_limited", HttpStatusCode.TooManyRequests)
        }
        val response = buildAddByTokenResponse(cartService, buyerUserId, request)
        IdempotencyService.IdempotentResponse(
            status = HttpStatusCode.OK,
            response = response,
            responseJson = serializeAddByTokenResponse(response)
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

private suspend fun buildAddByTokenResponse(
    cartService: CartService,
    buyerUserId: Long,
    request: CartAddByTokenRequest
): Any {
    return when (val result = cartService.addByToken(buyerUserId, request.token, request.qty, request.selectedVariantId)) {
        is CartAddResult.Added -> CartAddResponse(
            undoToken = result.undoToken,
            addedLineId = result.addedLineId,
            cart = result.cart
        )

        is CartAddResult.VariantRequired -> CartVariantRequiredResponse(
            status = "variant_required",
            listing = result.listing,
            availableVariants = result.availableVariants,
            requiredOptions = result.requiredOptions
        )
    }
}

private suspend fun handleUpdate(call: ApplicationCall, cartService: CartService) {
    val buyerUserId = call.requireUserId()
    val payload = call.receive<JsonObject>()
    val lineId = payload["lineId"]?.jsonPrimitive?.longOrNull ?: throw ApiError("invalid_request")
    val qty = payload["qty"]?.jsonPrimitive?.intOrNull
    val variantProvided = payload.containsKey("variantId")
    val variantId = payload["variantId"]?.jsonPrimitive?.contentOrNull
    val remove = payload["remove"]?.jsonPrimitive?.booleanOrNull ?: false

    if (!remove && qty == null && !variantProvided) {
        throw ApiError("invalid_request", HttpStatusCode.BadRequest)
    }

    val cart = cartService.updateWithOptions(
        buyerUserId = buyerUserId,
        lineId = lineId,
        qty = qty,
        variantId = variantId,
        remove = remove,
        variantUpdateRequested = variantProvided
    )
    call.respond(CartResponse(cart = cart))
}

private suspend fun handleUndo(call: ApplicationCall, cartService: CartService) {
    val buyerUserId = call.requireUserId()
    val req = call.receive<CartUndoRequest>()
    val undoToken = QuickAddRequestValidation.normalizeToken(req.undoToken)
    val cart = cartService.undo(buyerUserId, undoToken)
    call.respond(CartResponse(cart = cart))
}

private suspend fun handleGetCart(call: ApplicationCall, cartService: CartService, cfg: AppConfig) {
    val buyerUserId = call.requireUserId()
    val cart = cartService.getCart(buyerUserId, cfg.merchants.defaultMerchantId)
    call.respond(CartResponse(cart = cart))
}

private fun serializeAddByTokenResponse(response: Any): String = when (response) {
    is CartAddResponse -> CART_IDEMPOTENCY_JSON.encodeToString(response)
    is CartVariantRequiredResponse -> CART_IDEMPOTENCY_JSON.encodeToString(response)
    else -> throw ApiError("invalid_request", HttpStatusCode.InternalServerError)
}

private const val IDEMPOTENCY_SCOPE_CART_ADD_BY_TOKEN = "cart_add_by_token"
private val CART_IDEMPOTENCY_JSON = Json { encodeDefaults = true }

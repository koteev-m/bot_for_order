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
import com.example.app.services.UserActionRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun Route.registerCartRoutes(
    cartService: CartService,
    cfg: AppConfig,
    rateLimiter: UserActionRateLimiter
) {
    post("/cart/add_by_token") {
        handleAddByToken(call, cartService, rateLimiter)
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
    rateLimiter: UserActionRateLimiter
) {
    val buyerUserId = call.requireUserId()
    val req = call.receive<CartAddByTokenRequest>()
    if (req.token.isBlank()) throw ApiError("invalid_request")
    if (!rateLimiter.allowAdd(buyerUserId)) {
        throw ApiError("rate_limited", HttpStatusCode.TooManyRequests)
    }

    when (val result = cartService.addByToken(buyerUserId, req.token, req.qty, req.selectedVariantId)) {
        is CartAddResult.Added -> call.respond(
            CartAddResponse(
                undoToken = result.undoToken,
                addedLineId = result.addedLineId,
                cart = result.cart
            )
        )
        is CartAddResult.VariantRequired -> call.respond(
            CartVariantRequiredResponse(
                status = "variant_required",
                listing = result.listing,
                availableVariants = result.availableVariants,
                requiredOptions = result.requiredOptions
            )
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
    if (req.undoToken.isBlank()) throw ApiError("invalid_request")
    val cart = cartService.undo(buyerUserId, req.undoToken)
    call.respond(CartResponse(cart = cart))
}

private suspend fun handleGetCart(call: ApplicationCall, cartService: CartService, cfg: AppConfig) {
    val buyerUserId = call.requireUserId()
    val cart = cartService.getCart(buyerUserId, cfg.merchants.defaultMerchantId)
    call.respond(CartResponse(cart = cart))
}

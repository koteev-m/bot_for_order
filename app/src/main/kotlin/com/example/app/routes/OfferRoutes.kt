package com.example.app.routes

import com.example.app.api.OfferDecisionResponse
import com.example.app.api.OfferRequest
import com.example.app.api.requireUserId
import com.example.db.VariantsRepository
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.registerOfferRoutes(variantsRepo: VariantsRepository) {
    post("/offer") {
        handleOffer(call, variantsRepo)
    }
}

private suspend fun handleOffer(call: ApplicationCall, variantsRepo: VariantsRepository) {
    call.requireUserId()
    val req = call.receive<OfferRequest>()
    validateOfferRequest(req, variantsRepo)
    call.respond(OfferDecisionResponse(decision = "reject"))
}

private suspend fun validateOfferRequest(req: OfferRequest, variantsRepo: VariantsRepository) {
    ensure(req.itemId.isNotBlank()) { "itemId required" }
    ensure(req.qty > 0) { "qty must be > 0" }
    ensure(req.offerAmountMinor > 0) { "offerAmountMinor must be > 0" }

    req.variantId?.let { variantId ->
        val variant = findVariantForItem(variantId, req.itemId, variantsRepo)
        ensure(variant.active && variant.stock > 0) { "variant not available" }
    }
}

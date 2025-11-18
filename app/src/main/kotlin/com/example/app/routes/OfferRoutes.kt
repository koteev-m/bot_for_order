package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.OfferAcceptRequest
import com.example.app.api.OfferAcceptResponse
import com.example.app.api.OfferDecisionResponse
import com.example.app.api.OfferRequest
import com.example.app.security.requireUserId
import com.example.app.services.OffersService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.registerOfferRoutes(offersService: OffersService) {
    post("/offer") {
        handleOffer(call, offersService)
    }
    post("/offer/accept") {
        handleOfferAccept(call, offersService)
    }
}

private suspend fun handleOffer(call: ApplicationCall, offersService: OffersService) {
    val userId = call.requireUserId()
    val req = call.receive<OfferRequest>()
    val result = try {
        offersService.createAndEvaluate(
            userId = userId,
            itemId = req.itemId,
            variantId = req.variantId,
            qty = req.qty,
            offerMinor = req.offerAmountMinor
        )
    } catch (e: IllegalArgumentException) {
        throw ApiError(e.message ?: "invalid request", cause = e)
    } catch (e: IllegalStateException) {
        throw ApiError(e.message ?: "invalid state", cause = e)
    }

    call.respond(
        OfferDecisionResponse(
            decision = result.decision.apiValue,
            counterAmountMinor = result.counterAmountMinor,
            ttlSec = result.ttlSec
        )
    )
}

private suspend fun handleOfferAccept(call: ApplicationCall, offersService: OffersService) {
    val userId = call.requireUserId()
    val req = call.receive<OfferAcceptRequest>()
    val qty = req.qty ?: 1
    val result = try {
        offersService.acceptOffer(userId, req.offerId, qty)
    } catch (e: IllegalArgumentException) {
        throw ApiError(e.message ?: "invalid request", cause = e)
    } catch (e: IllegalStateException) {
        throw ApiError(e.message ?: "invalid state", cause = e)
    }
    call.respond(
        HttpStatusCode.Accepted,
        OfferAcceptResponse(orderId = result.orderId, status = result.status.name)
    )
}

package com.example.app.routes

import com.example.app.api.BuyerDeliveryProfileRequest
import com.example.app.api.BuyerDeliveryProfileResponse
import com.example.app.security.requireUserId
import com.example.app.services.DeliveryFieldsCodec
import com.example.app.services.DeliveryService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.registerBuyerDeliveryRoutes(deliveryService: DeliveryService) {
    get("/buyer/delivery_profile") {
        handleGetBuyerDeliveryProfile(call, deliveryService)
    }
    post("/buyer/delivery_profile") {
        handleSetBuyerDeliveryProfile(call, deliveryService)
    }
}

private suspend fun handleGetBuyerDeliveryProfile(
    call: ApplicationCall,
    deliveryService: DeliveryService
) {
    val userId = call.requireUserId()
    val profile = deliveryService.getBuyerDeliveryProfile(userId)
    if (profile == null) {
        call.respond(BuyerDeliveryProfileResponse(fields = null, updatedAt = null))
        return
    }
    call.respond(
        BuyerDeliveryProfileResponse(
            fields = DeliveryFieldsCodec.decodeFields(profile.fieldsJson),
            updatedAt = profile.updatedAt.toString()
        )
    )
}

private suspend fun handleSetBuyerDeliveryProfile(
    call: ApplicationCall,
    deliveryService: DeliveryService
) {
    val userId = call.requireUserId()
    val request = call.receive<BuyerDeliveryProfileRequest>()
    val profile = deliveryService.setBuyerDeliveryProfile(userId, request.fields)
    call.respond(
        BuyerDeliveryProfileResponse(
            fields = DeliveryFieldsCodec.decodeFields(profile.fieldsJson),
            updatedAt = profile.updatedAt.toString()
        )
    )
}

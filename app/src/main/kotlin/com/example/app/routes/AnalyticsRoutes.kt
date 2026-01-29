package com.example.app.routes

import com.example.app.api.AnalyticsEventRequest
import com.example.app.api.ApiError
import com.example.app.api.SimpleResponse
import com.example.app.config.AppConfig
import com.example.app.security.requireUserId
import com.example.db.EventLogRepository
import com.example.domain.EventLogEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_EVENT_TYPE_LENGTH = 64
private const val MAX_FIELD_LENGTH = 64
private const val MAX_METADATA_BYTES = 8 * 1024
private val ANALYTICS_JSON = Json { encodeDefaults = false; explicitNulls = false }

fun Route.registerAnalyticsRoutes(
    eventLogRepository: EventLogRepository,
    cfg: AppConfig
) {
    post("/analytics/event") {
        handleAnalyticsEvent(call, eventLogRepository, cfg)
    }
}

private suspend fun handleAnalyticsEvent(
    call: ApplicationCall,
    eventLogRepository: EventLogRepository,
    cfg: AppConfig
) {
    val userId = call.requireUserId()
    val request = call.receive<AnalyticsEventRequest>()
    val eventType = request.eventType.trim()
    if (eventType.isEmpty() || eventType.length > MAX_EVENT_TYPE_LENGTH) {
        throw ApiError("invalid_event_type", HttpStatusCode.BadRequest)
    }
    val storefrontId = request.storefrontId?.trim()
    if (storefrontId != null && storefrontId.length > MAX_FIELD_LENGTH) {
        throw ApiError("invalid_storefront_id", HttpStatusCode.BadRequest)
    }
    val listingId = request.listingId?.trim()
    if (listingId != null && listingId.length > MAX_FIELD_LENGTH) {
        throw ApiError("invalid_listing_id", HttpStatusCode.BadRequest)
    }
    val variantId = request.variantId?.trim()
    if (variantId != null && variantId.length > MAX_FIELD_LENGTH) {
        throw ApiError("invalid_variant_id", HttpStatusCode.BadRequest)
    }
    val metadataJson = request.metadata?.let { ANALYTICS_JSON.encodeToString(it) }
    if (metadataJson != null && metadataJson.toByteArray().size > MAX_METADATA_BYTES) {
        throw ApiError("invalid_metadata", HttpStatusCode.BadRequest)
    }

    val entry = EventLogEntry(
        ts = Instant.now(),
        eventType = eventType,
        buyerUserId = userId,
        merchantId = cfg.merchants.defaultMerchantId,
        storefrontId = storefrontId,
        channelId = request.channelId,
        postMessageId = request.postMessageId,
        listingId = listingId,
        variantId = variantId,
        metadataJson = metadataJson
    )
    eventLogRepository.insert(entry)
    call.respond(SimpleResponse())
}

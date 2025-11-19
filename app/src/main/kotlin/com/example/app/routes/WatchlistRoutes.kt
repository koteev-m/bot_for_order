package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.SimpleResponse
import com.example.app.api.WatchlistSubscribeRequest
import com.example.app.config.AppConfig
import com.example.app.security.requireUserId
import com.example.db.ItemsRepository
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.WatchTrigger
import com.example.domain.watchlist.PriceDropSubscription
import com.example.domain.watchlist.WatchlistRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.registerWatchlistRoutes(
    itemsRepo: ItemsRepository,
    watchlistRepository: WatchlistRepository,
    cfg: AppConfig
) {
    route("/watchlist") {
        post {
            ensureFeatureEnabled(cfg)
            val userId = call.requireUserId()
            val request = call.receive<WatchlistSubscribeRequest>()
            requirePriceDropTrigger(request.trigger)
            val itemId = requireItemId(request.itemId)
            val item = itemsRepo.requireActiveItem(itemId)
            val target = validateTargetMinor(request.targetMinor)
            watchlistRepository.upsertPriceDrop(
                PriceDropSubscription(
                    userId = userId,
                    itemId = item.id,
                    targetMinor = target
                )
            )
            call.respond(SimpleResponse())
        }

        delete {
            ensureFeatureEnabled(cfg)
            val userId = call.requireUserId()
            val itemId = requireItemId(call.request.queryParameters["itemId"])
            val triggerParam = call.request.queryParameters["trigger"] ?: "price_drop"
            requirePriceDropTrigger(triggerParam)
            watchlistRepository.deletePriceDrop(userId, itemId)
            call.respond(SimpleResponse())
        }
    }
}

private fun ensureFeatureEnabled(cfg: AppConfig) {
    if (!cfg.server.watchlistPriceDropEnabled) {
        throw ApiError("feature_disabled", HttpStatusCode.BadRequest)
    }
}

private fun parseTrigger(raw: String): WatchTrigger =
    runCatching { WatchTrigger.valueOf(raw.trim().uppercase()) }
        .getOrElse { throw ApiError("invalid_trigger") }

private fun requirePriceDropTrigger(raw: String): WatchTrigger {
    val trigger = parseTrigger(raw)
    if (trigger != WatchTrigger.PRICE_DROP) {
        throw ApiError("unsupported_trigger")
    }
    return trigger
}

private fun requireItemId(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApiError("itemId_required")

private suspend fun ItemsRepository.requireActiveItem(itemId: String): Item {
    val item = getById(itemId) ?: throw ApiError("item_not_found", HttpStatusCode.NotFound)
    if (item.status != ItemStatus.active) {
        throw ApiError("item_inactive", HttpStatusCode.BadRequest)
    }
    return item
}

private fun validateTargetMinor(targetMinor: Long?): Long? {
    if (targetMinor != null && targetMinor < 0) {
        throw ApiError("invalid_target")
    }
    return targetMinor
}

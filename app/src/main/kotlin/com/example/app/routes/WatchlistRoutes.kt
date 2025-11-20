package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.SimpleResponse
import com.example.app.api.WatchlistSubscribeRequest
import com.example.app.config.AppConfig
import com.example.app.security.requireUserId
import com.example.db.ItemsRepository
import com.example.db.VariantsRepository
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.WatchTrigger
import com.example.domain.watchlist.PriceDropSubscription
import com.example.domain.watchlist.RestockSubscription
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
    variantsRepo: VariantsRepository,
    watchlistRepository: WatchlistRepository,
    cfg: AppConfig
) {
    route("/watchlist") {
        post {
            val userId = call.requireUserId()
            val request = call.receive<WatchlistSubscribeRequest>()
            val trigger = parseTrigger(request.trigger)
            ensureFeatureEnabled(cfg, trigger)
            val itemId = requireItemId(request.itemId)
            val item = itemsRepo.requireActiveItem(itemId)
            when (trigger) {
                WatchTrigger.PRICE_DROP -> {
                    val target = validateTargetMinor(request.targetMinor)
                    watchlistRepository.upsertPriceDrop(
                        PriceDropSubscription(
                            userId = userId,
                            itemId = item.id,
                            targetMinor = target
                        )
                    )
                }
                WatchTrigger.RESTOCK -> {
                    val variantId = normalizeVariantId(request.variantId)
                    val validatedVariant = variantId?.let { findVariantForItem(it, item.id, variantsRepo).id }
                    watchlistRepository.upsertRestock(
                        RestockSubscription(
                            userId = userId,
                            itemId = item.id,
                            variantId = validatedVariant
                        )
                    )
                }
            }
            call.respond(SimpleResponse())
        }

        delete {
            val userId = call.requireUserId()
            val itemId = requireItemId(call.request.queryParameters["itemId"])
            val triggerParam = call.request.queryParameters["trigger"] ?: "price_drop"
            val trigger = parseTrigger(triggerParam)
            ensureFeatureEnabled(cfg, trigger)
            val variantId = normalizeVariantId(call.request.queryParameters["variantId"])
            when (trigger) {
                WatchTrigger.PRICE_DROP -> watchlistRepository.deletePriceDrop(userId, itemId)
                WatchTrigger.RESTOCK -> watchlistRepository.deleteRestock(userId, itemId, variantId)
            }
            call.respond(SimpleResponse())
        }
    }
}

private fun ensureFeatureEnabled(cfg: AppConfig, trigger: WatchTrigger) {
    when (trigger) {
        WatchTrigger.PRICE_DROP -> if (!cfg.server.watchlistPriceDropEnabled) {
            throw ApiError("feature_disabled", HttpStatusCode.BadRequest)
        }
        WatchTrigger.RESTOCK -> if (!cfg.server.watchlistRestockEnabled) {
            throw ApiError("feature_disabled", HttpStatusCode.BadRequest)
        }
    }
}

private fun parseTrigger(raw: String): WatchTrigger =
    runCatching { WatchTrigger.valueOf(raw.trim().uppercase()) }
        .getOrElse { throw ApiError("invalid_trigger") }

private fun requireItemId(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApiError("itemId_required")

private fun normalizeVariantId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

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

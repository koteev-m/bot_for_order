package com.example.app.services

import com.example.domain.watchlist.RestockNotifier
import com.example.domain.watchlist.WatchlistRepository
import org.slf4j.LoggerFactory

class RestockAlertService(
    private val watchlistRepository: WatchlistRepository,
    private val restockNotifier: RestockNotifier,
    private val enabled: Boolean
) {

    private val log = LoggerFactory.getLogger(RestockAlertService::class.java)

    suspend fun dispatch(itemId: String, variantId: String?): Int {
        if (!enabled) {
            return 0
        }
        val variantSubs = if (variantId != null) {
            watchlistRepository.listRestockByItemVariant(itemId, variantId)
        } else {
            emptyList()
        }
        val generalSubs = watchlistRepository.listRestockByItemVariant(itemId, null)
        val notified = mutableSetOf<Long>()
        var attempts = 0
        variantSubs.forEach { sub ->
            notified += sub.userId
            sendRestock(sub.userId, sub.itemId, variantId)
            attempts++
        }
        generalSubs.forEach { sub ->
            if (variantId == null || notified.add(sub.userId)) {
                sendRestock(sub.userId, sub.itemId, null)
                attempts++
            }
        }
        if (attempts > 0) {
            log.info("restock_dispatch item={} variant={} targets={}", itemId, variantId ?: "_", attempts)
        }
        return attempts
    }

    private suspend fun sendRestock(userId: Long, itemId: String, variantId: String?) {
        runCatching { restockNotifier.notify(userId, itemId, variantId) }
            .onFailure { error ->
                log.warn(
                    "restock_notify_failed item={} variant={} cause={}",
                    itemId,
                    variantId ?: "_",
                    error.message
                )
            }
    }
}

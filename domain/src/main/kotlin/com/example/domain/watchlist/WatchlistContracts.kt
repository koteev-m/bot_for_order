package com.example.domain.watchlist

data class PriceDropSubscription(
    val userId: Long,
    val itemId: String,
    val targetMinor: Long?
)

interface WatchlistRepository {
    suspend fun upsertPriceDrop(sub: PriceDropSubscription)

    suspend fun deletePriceDrop(userId: Long, itemId: String)

    suspend fun listPriceDropByItem(itemId: String): List<PriceDropSubscription>
}

interface PriceDropNotifier {
    suspend fun notify(userId: Long, itemId: String, currentPriceMinor: Long)
}

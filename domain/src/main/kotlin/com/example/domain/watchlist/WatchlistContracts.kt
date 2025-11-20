package com.example.domain.watchlist

data class PriceDropSubscription(
    val userId: Long,
    val itemId: String,
    val targetMinor: Long?
)

data class RestockSubscription(
    val userId: Long,
    val itemId: String,
    val variantId: String?
)

interface WatchlistRepository {
    suspend fun upsertPriceDrop(sub: PriceDropSubscription)

    suspend fun deletePriceDrop(userId: Long, itemId: String)

    suspend fun listPriceDropByItem(itemId: String): List<PriceDropSubscription>

    suspend fun upsertRestock(sub: RestockSubscription)

    suspend fun deleteRestock(userId: Long, itemId: String, variantId: String?)

    suspend fun listRestockByItemVariant(itemId: String, variantId: String?): List<RestockSubscription>

    suspend fun listRestockSubscriptions(): List<RestockSubscription>
}

interface PriceDropNotifier {
    suspend fun notify(userId: Long, itemId: String, currentPriceMinor: Long)
}

interface RestockNotifier {
    suspend fun notify(userId: Long, itemId: String, variantId: String?)
}

package com.example.domain.hold

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReserveSource {
    @SerialName("offer")
    OFFER,

    @SerialName("order")
    ORDER
}

@Serializable
data class StockReservePayload(
    val itemId: String,
    val variantId: String?,
    val qty: Int,
    val userId: Long?,
    val ttlSec: Long,
    val from: ReserveSource,
    val offerId: String? = null
)

enum class ReserveWriteResult {
    CREATED,
    REFRESHED
}

interface HoldService {
    suspend fun createOfferReserve(
        offerId: String,
        payload: StockReservePayload,
        ttlSec: Long
    ): ReserveWriteResult

    suspend fun createOrderReserve(
        orderId: String,
        payload: StockReservePayload,
        ttlSec: Long
    ): ReserveWriteResult

    suspend fun convertOfferToOrderReserve(
        offerId: String,
        orderId: String,
        extendTtlSec: Long,
        updatePayload: (StockReservePayload) -> StockReservePayload
    ): Boolean

    suspend fun deleteReserveByOrder(orderId: String): Boolean
    suspend fun deleteReserveByOffer(offerId: String): Boolean
    suspend fun hasOrderReserve(orderId: String): Boolean
    suspend fun releaseExpired()
}

/**
 * Универсальный распределённый замок с ожиданием и сроком жизни.
 */
interface LockManager {
    /**
     * @param waitMs  сколько ждать освобождения (0 — не ждать)
     * @param leaseMs сколько держать замок до авто-освобождения
     */
    suspend fun <T> withLock(key: String, waitMs: Long, leaseMs: Long, action: suspend () -> T): T
}

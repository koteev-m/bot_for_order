package com.example.domain.hold

data class OrderHoldRequest(
    val listingId: String,
    val variantId: String?,
    val qty: Int
)

interface OrderHoldService {
    suspend fun tryAcquire(orderId: String, holds: List<OrderHoldRequest>, ttlSec: Long): Boolean
    suspend fun extend(orderId: String, holds: List<OrderHoldRequest>, ttlSec: Long): Boolean
    suspend fun release(orderId: String, holds: List<OrderHoldRequest>)
    suspend fun hasActive(orderId: String, holds: List<OrderHoldRequest>): Boolean
}

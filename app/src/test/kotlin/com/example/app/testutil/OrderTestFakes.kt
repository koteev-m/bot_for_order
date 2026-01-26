package com.example.app.testutil

import com.example.app.services.OrderDedupStore
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldRequest
import com.example.domain.hold.OrderHoldService
import com.example.domain.hold.ReserveWriteResult
import com.example.domain.hold.StockReservePayload
import com.example.app.services.ManualPaymentsNotifier
import com.example.domain.Order
import com.example.domain.OrderPaymentClaim
import com.example.domain.PaymentMethodMode
import com.example.app.storage.Storage
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.io.InputStream
import java.time.Duration

class InMemoryOrderHoldService(
    private val nowProvider: () -> Instant = { Instant.now() }
) : OrderHoldService {
    private data class HoldEntry(
        val orderId: String,
        val expiresAt: Instant
    )

    private val holds = ConcurrentHashMap<String, HoldEntry>()

    override suspend fun tryAcquire(orderId: String, holds: List<OrderHoldRequest>, ttlSec: Long): Boolean {
        val acquired = mutableListOf<String>()
        val now = nowProvider()
        val expiresAt = now.plusSeconds(ttlSec.coerceAtLeast(1))
        for (hold in holds) {
            val key = keyFor(hold)
            val existing = holdsMapCleaned(key, now)
            if (existing != null && existing.orderId != orderId) {
                releaseKeys(orderId, acquired)
                return false
            }
            this.holds[key] = HoldEntry(orderId, expiresAt)
            acquired.add(key)
        }
        return true
    }

    override suspend fun extend(orderId: String, holds: List<OrderHoldRequest>, ttlSec: Long): Boolean {
        val now = nowProvider()
        val expiresAt = now.plusSeconds(ttlSec.coerceAtLeast(1))
        var ok = true
        holds.forEach { hold ->
            val key = keyFor(hold)
            val existing = holdsMapCleaned(key, now)
            if (existing?.orderId == orderId) {
                this.holds[key] = HoldEntry(orderId, expiresAt)
            } else {
                ok = false
            }
        }
        return ok
    }

    override suspend fun release(orderId: String, holds: List<OrderHoldRequest>) {
        holds.forEach { hold ->
            val key = keyFor(hold)
            val existing = this.holds[key]
            if (existing?.orderId == orderId) {
                this.holds.remove(key)
            }
        }
    }

    override suspend fun hasActive(orderId: String, holds: List<OrderHoldRequest>): Boolean {
        val now = nowProvider()
        if (holds.isEmpty()) return false
        return holds.all { hold ->
            val key = keyFor(hold)
            holdsMapCleaned(key, now)?.orderId == orderId
        }
    }

    private fun holdsMapCleaned(key: String, now: Instant): HoldEntry? {
        val existing = holds[key]
        if (existing != null && existing.expiresAt.isBefore(now)) {
            holds.remove(key)
            return null
        }
        return existing
    }

    private fun keyFor(hold: OrderHoldRequest): String {
        val resourceKey = hold.variantId ?: hold.listingId
        val prefix = if (hold.variantId == null) "listing" else "variant"
        return "order_hold:$prefix:$resourceKey"
    }

    private fun releaseKeys(orderId: String, keys: List<String>) {
        keys.forEach { key ->
            val existing = holds[key]
            if (existing?.orderId == orderId) {
                holds.remove(key)
            }
        }
    }
}

class InMemoryOrderDedupStore(
    private val nowProvider: () -> Instant = { Instant.now() }
) : OrderDedupStore {
    private data class StoredValue(
        val value: String,
        val expiresAt: Instant
    )

    private val storage = ConcurrentHashMap<String, StoredValue>()

    override fun get(key: String): String? {
        val now = nowProvider()
        val existing = storage[key] ?: return null
        if (!existing.expiresAt.isAfter(now)) {
            storage.remove(key)
            return null
        }
        return existing.value
    }

    override fun set(key: String, value: String, ttlSec: Int) {
        val now = nowProvider()
        storage[key] = StoredValue(value, now.plusSeconds(ttlSec.coerceAtLeast(1).toLong()))
    }

    override fun delete(key: String) {
        storage.remove(key)
    }
}

class InMemoryHoldService(
    private val nowProvider: () -> Instant = { Instant.now() }
) : HoldService {
    private data class ReserveEntry(
        val payload: StockReservePayload,
        val expiresAt: Instant
    )

    private val orderReserves = ConcurrentHashMap<String, ReserveEntry>()
    private val offerReserves = ConcurrentHashMap<String, ReserveEntry>()

    override suspend fun createOfferReserve(
        offerId: String,
        payload: StockReservePayload,
        ttlSec: Long
    ): ReserveWriteResult {
        val result = if (offerReserves.containsKey(offerId)) ReserveWriteResult.REFRESHED else ReserveWriteResult.CREATED
        offerReserves[offerId] = ReserveEntry(payload, nowProvider().plusSeconds(ttlSec.coerceAtLeast(1)))
        return result
    }

    override suspend fun createOrderReserve(
        orderId: String,
        payload: StockReservePayload,
        ttlSec: Long
    ): ReserveWriteResult {
        val result = if (orderReserves.containsKey(orderId)) ReserveWriteResult.REFRESHED else ReserveWriteResult.CREATED
        orderReserves[orderId] = ReserveEntry(payload, nowProvider().plusSeconds(ttlSec.coerceAtLeast(1)))
        return result
    }

    override suspend fun convertOfferToOrderReserve(
        offerId: String,
        orderId: String,
        extendTtlSec: Long,
        updatePayload: (StockReservePayload) -> StockReservePayload
    ): Boolean {
        val existing = offerReserves.remove(offerId) ?: return false
        val updatedPayload = updatePayload(existing.payload)
        orderReserves[orderId] = ReserveEntry(
            updatedPayload,
            nowProvider().plusSeconds(extendTtlSec.coerceAtLeast(1))
        )
        return true
    }

    override suspend fun deleteReserveByOrder(orderId: String): Boolean {
        return orderReserves.remove(orderId) != null
    }

    override suspend fun deleteReserveByOffer(offerId: String): Boolean {
        return offerReserves.remove(offerId) != null
    }

    override suspend fun hasOrderReserve(orderId: String): Boolean {
        val now = nowProvider()
        val existing = orderReserves[orderId]
        if (existing != null && existing.expiresAt.isBefore(now)) {
            orderReserves.remove(orderId)
            return false
        }
        return existing != null
    }

    override suspend fun releaseExpired() {
        val now = nowProvider()
        orderReserves.entries.removeIf { it.value.expiresAt.isBefore(now) }
        offerReserves.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }
}

class NoopLockManager : LockManager {
    override suspend fun <T> withLock(key: String, waitMs: Long, leaseMs: Long, action: suspend () -> T): T = action()
}

class InMemoryStorage : Storage {
    val objects = ConcurrentHashMap<String, ByteArray>()
    override fun putObject(stream: InputStream, key: String, contentType: String, size: Long) {
        objects[key] = stream.readBytes()
    }

    override fun presignGet(key: String, ttl: Duration): String {
        return "https://storage.local/$key?ttl=${ttl.seconds}"
    }
}

class FakeManualPaymentsNotifier : ManualPaymentsNotifier {
    val adminNotifications = mutableListOf<String>()
    val buyerNotifications = mutableListOf<Long>()

    override fun notifyAdminClaim(order: Order, claim: OrderPaymentClaim, attachmentCount: Int, mode: PaymentMethodMode) {
        adminNotifications.add(order.id)
    }

    override fun notifyBuyerClarification(order: Order) {
        buyerNotifications.add(order.userId)
    }
}

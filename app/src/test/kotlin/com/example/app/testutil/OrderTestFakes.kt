package com.example.app.testutil

import com.example.app.services.OrderDedupStore
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldRequest
import com.example.domain.hold.OrderHoldService
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

class NoopLockManager : LockManager {
    override suspend fun <T> withLock(key: String, waitMs: Long, leaseMs: Long, action: suspend () -> T): T = action()
}

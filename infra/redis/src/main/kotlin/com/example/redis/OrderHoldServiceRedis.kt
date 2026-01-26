package com.example.redis

import com.example.domain.hold.OrderHoldRequest
import com.example.domain.hold.OrderHoldService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit

private const val ORDER_HOLD_PREFIX = "order_hold:"

class OrderHoldServiceRedis(
    private val redisson: RedissonClient
) : OrderHoldService {

    override suspend fun tryAcquire(orderId: String, holds: List<OrderHoldRequest>, ttlSec: Long): Boolean =
        withContext(Dispatchers.IO) {
            val acquired = mutableListOf<String>()
            val normalizedTtl = ttlSec.coerceAtLeast(1).toLong()
            for (hold in holds) {
                val key = keyFor(hold)
                val bucket = redisson.getBucket<String>(key)
                val inserted = bucket.trySet(orderId, normalizedTtl, TimeUnit.SECONDS)
                if (inserted) {
                    acquired.add(key)
                    continue
                }
                val existing = bucket.get()
                if (existing == orderId) {
                    bucket.set(orderId, normalizedTtl, TimeUnit.SECONDS)
                    acquired.add(key)
                    continue
                }
                releaseKeys(orderId, acquired)
                return@withContext false
            }
            true
        }

    override suspend fun extend(orderId: String, holds: List<OrderHoldRequest>, ttlSec: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (holds.isEmpty()) return@withContext true
            val normalizedTtl = ttlSec.coerceAtLeast(1).toLong()
            var allPresent = true
            holds.forEach { hold ->
                val key = keyFor(hold)
                val bucket = redisson.getBucket<String>(key)
                val existing = bucket.get()
                if (existing == orderId) {
                    bucket.set(orderId, normalizedTtl, TimeUnit.SECONDS)
                } else {
                    allPresent = false
                }
            }
            allPresent
        }

    override suspend fun release(orderId: String, holds: List<OrderHoldRequest>) = withContext(Dispatchers.IO) {
        if (holds.isEmpty()) return@withContext
        holds.forEach { hold ->
            val key = keyFor(hold)
            val bucket = redisson.getBucket<String>(key)
            if (bucket.get() == orderId) {
                bucket.delete()
            }
        }
    }

    override suspend fun hasActive(orderId: String, holds: List<OrderHoldRequest>): Boolean =
        withContext(Dispatchers.IO) {
            if (holds.isEmpty()) return@withContext false
            holds.all { hold ->
                val key = keyFor(hold)
                val bucket = redisson.getBucket<String>(key)
                bucket.get() == orderId
            }
        }

    private fun keyFor(hold: OrderHoldRequest): String {
        val resourceKey = hold.variantId ?: hold.listingId
        val prefix = if (hold.variantId == null) "listing" else "variant"
        return "$ORDER_HOLD_PREFIX$prefix:$resourceKey"
    }

    private fun releaseKeys(orderId: String, keys: List<String>) {
        keys.forEach { key ->
            val bucket = redisson.getBucket<String>(key)
            if (bucket.get() == orderId) {
                bucket.delete()
            }
        }
    }
}

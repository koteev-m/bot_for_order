package com.example.redis

import com.example.domain.hold.LockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit

class LockManagerRedis(
    private val redisson: RedissonClient
) : LockManager {

    override suspend fun <T> withLock(key: String, waitMs: Long, leaseMs: Long, action: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            val lockKey = "lock:$key"
            val lock: RLock = redisson.getLock(lockKey)

            val acquired = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS)
            if (!acquired) error("Lock acquisition failed for key=$lockKey (waitMs=$waitMs)")

            try {
                action()
            } finally {
                if (lock.isHeldByCurrentThread) {
                    lock.unlock()
                }
            }
        }
}

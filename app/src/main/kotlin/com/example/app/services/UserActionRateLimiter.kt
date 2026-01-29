package com.example.app.services

import com.example.app.config.UserActionRateLimitConfig
import java.time.Clock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

// In-memory limiter: for multi-node deployments use a shared store (Redis).
class UserActionRateLimiter(
    private val config: UserActionRateLimitConfig,
    private val clock: Clock = Clock.systemUTC()
) {
    private val buckets = ConcurrentHashMap<Action, ConcurrentHashMap<Long, Bucket>>()

    fun allowResolve(userId: Long): Boolean =
        allow(Action.RESOLVE, userId, config.resolveMax, config.resolveWindowSeconds)

    fun allowAdd(userId: Long): Boolean =
        allow(Action.ADD, userId, config.addMax, config.addWindowSeconds)

    fun allowClaim(userId: Long): Boolean =
        allow(Action.CLAIM, userId, config.claimMax, config.claimWindowSeconds)

    private fun allow(action: Action, userId: Long, max: Int, windowSeconds: Int): Boolean {
        if (max <= 0 || windowSeconds <= 0) return true
        val perAction = buckets.computeIfAbsent(action) { ConcurrentHashMap() }
        val bucket = perAction.computeIfAbsent(userId) { Bucket(ArrayDeque(), Any()) }
        val nowMs = clock.millis()
        val windowMs = windowSeconds * 1_000L
        synchronized(bucket.lock) {
            while (bucket.timestamps.isNotEmpty() && bucket.timestamps.first() <= nowMs - windowMs) {
                bucket.timestamps.removeFirst()
            }
            if (bucket.timestamps.size >= max) {
                return false
            }
            bucket.timestamps.addLast(nowMs)
        }
        return true
    }

    private data class Bucket(
        val timestamps: ArrayDeque<Long>,
        val lock: Any
    )

    private enum class Action {
        RESOLVE,
        ADD,
        CLAIM
    }
}

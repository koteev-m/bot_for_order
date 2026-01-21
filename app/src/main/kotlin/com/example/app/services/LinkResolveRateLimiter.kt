package com.example.app.services

import com.example.app.config.LinkResolveRateLimitConfig
import java.util.concurrent.TimeUnit
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory

class LinkResolveRateLimiter(
    private val redisson: RedissonClient,
    private val tokenHasher: LinkTokenHasher,
    private val config: LinkResolveRateLimitConfig
) {
    private val log = LoggerFactory.getLogger(LinkResolveRateLimiter::class.java)

    fun allow(userId: Long, token: String): Boolean {
        val tokenHash = tokenHasher.hash(token)
        val key = "rl:link_resolve:$userId:$tokenHash"
        return try {
            val counter = redisson.getAtomicLong(key)
            val value = counter.incrementAndGet()
            if (value == 1L) {
                counter.expire(config.windowSeconds.toLong(), TimeUnit.SECONDS)
            }
            value <= config.max
        } catch (e: Exception) {
            log.warn("Link resolve rate limit failed for key {}", key, e)
            true
        }
    }
}

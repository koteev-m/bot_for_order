package com.example.app.services

import com.example.app.config.LinkResolveRateLimitConfig
import org.redisson.api.RScript
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
            val result = redisson.getScript().eval<Long>(
                RScript.Mode.READ_WRITE,
                RATE_LIMIT_SCRIPT,
                RScript.ReturnType.INTEGER,
                listOf(key),
                config.windowSeconds,
                config.max
            )
            result <= config.max.toLong()
        } catch (e: Exception) {
            log.warn("Link resolve rate limit failed for key {}", key, e)
            true
        }
    }

    private companion object {
        private val RATE_LIMIT_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
        """.trimIndent()
    }
}

package com.example.app

import com.example.app.config.LinkResolveRateLimitConfig
import com.example.app.services.LinkResolveRateLimiter
import com.example.app.services.LinkTokenHasher
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.slf4j.Logger

class LinkResolveRateLimiterTest : StringSpec({
    "throttles rate limit warning logs" {
        val script = mockk<RScript>()
        every {
            script.eval<Long>(
                any<RScript.Mode>(),
                any<String>(),
                any<RScript.ReturnType>(),
                any<List<String>>(),
                any<Int>(),
                any<Int>()
            )
        } throws RuntimeException("redis down")
        val redisson = mockk<RedissonClient> { every { getScript() } returns script }
        val tokenHasher = mockk<LinkTokenHasher> { every { hash(any()) } returns "hash" }
        val logger = mockk<Logger>(relaxed = true)

        var now = 0L
        val limiter = LinkResolveRateLimiter(
            redisson = redisson,
            tokenHasher = tokenHasher,
            config = LinkResolveRateLimitConfig(max = 1, windowSeconds = 1),
            nowProvider = { now },
            log = logger
        )

        limiter.allow(1L, "token")
        now += 10
        limiter.allow(1L, "token")
        now = 60_001L
        limiter.allow(1L, "token")

        verify(exactly = 1) {
            logger.warn("Link resolve rate limit failed for key {}", "rl:link_resolve:1:hash", any<Exception>())
        }
        verify(exactly = 1) {
            logger.warn(
                "Link resolve rate limit failed for key {} ({} warnings suppressed)",
                "rl:link_resolve:1:hash",
                1L,
                any<Exception>()
            )
        }
    }
})

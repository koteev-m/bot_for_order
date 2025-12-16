package com.example.app

import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.sql.Database
import org.redisson.api.RedissonClient
import org.redisson.api.redisnode.BaseRedisNodes
import org.redisson.api.redisnode.RedisNodes
import org.redisson.config.Config
import java.util.Base64

internal fun encodeBasicAuth(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())

internal fun healthDeps(): Pair<Database, RedissonClient> {
    val database = mockk<Database>()
    val redisConfig = Config().apply { useSingleServer().address = "redis://localhost:6379" }
    val nodesGroup = mockk<BaseRedisNodes> { every { pingAll() } returns true }
    val redisson = mockk<RedissonClient> {
        every { config } returns redisConfig
        every { getRedisNodes(any<RedisNodes<BaseRedisNodes>>()) } returns nodesGroup
    }
    return database to redisson
}

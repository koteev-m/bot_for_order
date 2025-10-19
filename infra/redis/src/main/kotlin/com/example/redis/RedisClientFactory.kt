package com.example.redis

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config

object RedisClientFactory {
    fun create(url: String): RedissonClient {
        val cfg = Config()
        cfg.useSingleServer().address = url
        return Redisson.create(cfg)
    }
}

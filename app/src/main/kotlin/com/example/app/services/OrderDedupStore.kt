package com.example.app.services

import java.util.concurrent.TimeUnit
import org.redisson.api.RedissonClient

interface OrderDedupStore {
    fun get(key: String): String?
    fun set(key: String, value: String, ttlSec: Int)
    fun delete(key: String)
}

class OrderDedupStoreRedisson(
    private val redisson: RedissonClient
) : OrderDedupStore {
    override fun get(key: String): String? = runCatching {
        redisson.getBucket<String>(key).get()
    }.getOrNull()

    override fun set(key: String, value: String, ttlSec: Int) {
        val bucket = redisson.getBucket<String>(key)
        bucket.set(value, ttlSec.toLong(), TimeUnit.SECONDS)
    }

    override fun delete(key: String) {
        runCatching {
            redisson.getBucket<String>(key).delete()
        }
    }
}

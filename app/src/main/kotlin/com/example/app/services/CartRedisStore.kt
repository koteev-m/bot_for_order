package com.example.app.services

import java.util.concurrent.TimeUnit
import org.redisson.api.RedissonClient

interface CartRedisStore {
    fun tryRegisterDedup(key: String, value: String, ttlSec: Int): String?
    fun overwriteDedup(key: String, value: String, ttlSec: Int)
    fun deleteDedupIfEquals(key: String, value: String)
    fun saveUndo(undoToken: String, cartItemId: Long, ttlSec: Int)
    fun consumeUndo(undoToken: String): Long?
}

class CartRedisStoreRedisson(
    private val redisson: RedissonClient
) : CartRedisStore {
    override fun tryRegisterDedup(key: String, value: String, ttlSec: Int): String? {
        return runCatching {
            val bucket = redisson.getBucket<String>(key)
            val inserted = bucket.trySet(value, ttlSec.toLong(), TimeUnit.SECONDS)
            if (inserted) {
                null
            } else {
                bucket.get()
            }
        }.getOrNull()
    }

    override fun overwriteDedup(key: String, value: String, ttlSec: Int) {
        runCatching {
            val bucket = redisson.getBucket<String>(key)
            bucket.set(value, ttlSec.toLong(), TimeUnit.SECONDS)
        }
    }

    override fun deleteDedupIfEquals(key: String, value: String) {
        runCatching {
            val bucket = redisson.getBucket<String>(key)
            if (bucket.get() == value) {
                bucket.delete()
            }
        }
    }

    override fun saveUndo(undoToken: String, cartItemId: Long, ttlSec: Int) {
        val bucket = redisson.getBucket<String>(undoBucketKey(undoToken))
        bucket.set(cartItemId.toString(), ttlSec.toLong(), TimeUnit.SECONDS)
    }

    override fun consumeUndo(undoToken: String): Long? {
        val bucket = redisson.getBucket<String>(undoBucketKey(undoToken))
        val value = bucket.getAndDelete()
        return value?.toLongOrNull()
    }

    private fun undoBucketKey(undoToken: String): String = "cart:undo:$undoToken"
}

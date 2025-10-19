package com.example.redis

import com.example.domain.hold.HoldService
import com.example.domain.hold.ReserveKey
import com.example.domain.hold.ReservePayload
import com.example.redis.json.RedisJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.redisson.api.RedissonClient
import java.util.concurrent.TimeUnit

class HoldServiceRedis(
    private val redisson: RedissonClient
) : HoldService {

    override suspend fun putIfAbsent(key: ReserveKey, payload: ReservePayload, ttlSec: Long): Boolean =
        withContext(Dispatchers.IO) {
            val bucket = redisson.getBucket<String>(key.toRedisKey())
            val json = RedisJson.instance.encodeToString(ReservePayload.serializer(), payload)
            bucket.trySet(json, ttlSec, TimeUnit.SECONDS)
        }

    override suspend fun get(key: ReserveKey): ReservePayload? = withContext(Dispatchers.IO) {
        val bucket = redisson.getBucket<String>(key.toRedisKey())
        val json = bucket.get() ?: return@withContext null
        RedisJson.instance.decodeFromString(ReservePayload.serializer(), json)
    }

    override suspend fun exists(key: ReserveKey): Boolean = withContext(Dispatchers.IO) {
        redisson.getBucket<Any>(key.toRedisKey()).isExists
    }

    override suspend fun prolong(key: ReserveKey, ttlSec: Long): Boolean = withContext(Dispatchers.IO) {
        redisson.getBucket<Any>(key.toRedisKey()).expire(ttlSec, TimeUnit.SECONDS)
    }

    override suspend fun release(key: ReserveKey): Boolean = withContext(Dispatchers.IO) {
        redisson.getBucket<Any>(key.toRedisKey()).delete()
    }
}

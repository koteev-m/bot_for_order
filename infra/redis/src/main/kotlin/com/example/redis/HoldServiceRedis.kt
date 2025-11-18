package com.example.redis

import com.example.domain.hold.HoldService
import com.example.domain.hold.ReserveSource
import com.example.domain.hold.ReserveWriteResult
import com.example.domain.hold.StockReservePayload
import com.example.redis.json.RedisJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import java.util.concurrent.TimeUnit

private const val OFFER_KEY_PREFIX = "reserve:offer:"
private const val ORDER_KEY_PREFIX = "reserve:order:"

class HoldServiceRedis(
    private val redisson: RedissonClient
) : HoldService {

    override suspend fun createOfferReserve(
        offerId: String,
        payload: StockReservePayload,
        ttlSec: Long
    ): ReserveWriteResult = upsertReserve(
        key = "$OFFER_KEY_PREFIX$offerId",
        payload = payload.copy(from = ReserveSource.OFFER, ttlSec = ttlSec, offerId = null),
        ttlSec = ttlSec
    )

    override suspend fun createOrderReserve(
        orderId: String,
        payload: StockReservePayload,
        ttlSec: Long
    ): ReserveWriteResult = upsertReserve(
        key = "$ORDER_KEY_PREFIX$orderId",
        payload = payload.copy(from = ReserveSource.ORDER, ttlSec = ttlSec),
        ttlSec = ttlSec
    )

    override suspend fun convertOfferToOrderReserve(
        offerId: String,
        orderId: String,
        extendTtlSec: Long,
        updatePayload: (StockReservePayload) -> StockReservePayload
    ): Boolean = withContext(Dispatchers.IO) {
        val offerRedisKey = "$OFFER_KEY_PREFIX$offerId"
        val existingJson = redisson.getBucket<String>(offerRedisKey).get() ?: return@withContext false
        val payload = decode(existingJson)
        val updatedPayload = updatePayload(payload).copy(
            from = ReserveSource.ORDER,
            ttlSec = extendTtlSec,
            offerId = payload.offerId ?: offerId
        )
        val updatedJson = encode(updatedPayload)
        val orderRedisKey = "$ORDER_KEY_PREFIX$orderId"
        val script = """
            local current = redis.call('GET', KEYS[1])
            if not current then return 0 end
            if current ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            redis.call('DEL', KEYS[1])
            return 1
        """.trimIndent()
        val ttlArg = extendTtlSec.coerceAtLeast(1).toString()
        val success = redisson.getScript(StringCodec.INSTANCE).eval<Boolean>(
            RScript.Mode.READ_WRITE,
            script,
            RScript.ReturnType.BOOLEAN,
            listOf(offerRedisKey, orderRedisKey),
            existingJson,
            updatedJson,
            ttlArg
        )
        success
    }

    override suspend fun deleteReserveByOrder(orderId: String): Boolean = withContext(Dispatchers.IO) {
        redisson.getBucket<Any>("$ORDER_KEY_PREFIX$orderId").delete()
    }

    override suspend fun deleteReserveByOffer(offerId: String): Boolean = withContext(Dispatchers.IO) {
        redisson.getBucket<Any>("$OFFER_KEY_PREFIX$offerId").delete()
    }

    override suspend fun hasOrderReserve(orderId: String): Boolean = withContext(Dispatchers.IO) {
        redisson.getBucket<Any>("$ORDER_KEY_PREFIX$orderId").isExists
    }

    override suspend fun releaseExpired() {
        // Redis удаляет ключи по TTL автоматически — оставляем точку расширения.
    }

    private suspend fun upsertReserve(
        key: String,
        payload: StockReservePayload,
        ttlSec: Long
    ): ReserveWriteResult = withContext(Dispatchers.IO) {
        val bucket = redisson.getBucket<String>(key)
        val json = encode(payload)
        val normalizedTtl = ttlSec.coerceAtLeast(1)
        if (bucket.trySet(json, normalizedTtl, TimeUnit.SECONDS)) {
            ReserveWriteResult.CREATED
        } else {
            bucket.set(json, normalizedTtl, TimeUnit.SECONDS)
            ReserveWriteResult.REFRESHED
        }
    }

    private fun encode(payload: StockReservePayload): String =
        RedisJson.instance.encodeToString(StockReservePayload.serializer(), payload)

    private fun decode(json: String): StockReservePayload =
        RedisJson.instance.decodeFromString(StockReservePayload.serializer(), json)
}

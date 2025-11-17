package com.example.domain.hold

import kotlinx.serialization.Serializable

@Serializable
enum class ReserveKind { OFFER, ORDER }

@Serializable
data class ReserveKey(
    val kind: ReserveKind,
    val id: String
) {
    fun toRedisKey(): String = "reserve:${kind.name.lowercase()}:$id"
}

@Serializable
data class ReservePayload(
    val itemId: String,
    val variantId: String?,
    val qty: Int,
    val userId: Long?,
    val createdAtEpochSec: Long,
    val ttlSec: Long
)

/**
 * Управляет резервами с ограниченным временем жизни.
 */
interface HoldService {
    /**
     * Атомарно создаёт резерв, если ключ отсутствует (идемпотентность).
     * @return true — создан, false — уже существует
     */
    suspend fun putIfAbsent(key: ReserveKey, payload: ReservePayload, ttlSec: Long): Boolean

    suspend fun get(key: ReserveKey): ReservePayload?
    suspend fun exists(key: ReserveKey): Boolean

    /** Продлить TTL существующего резерва. */
    suspend fun prolong(key: ReserveKey, ttlSec: Long): Boolean

    /** Снять резерв (удалить ключ). */
    suspend fun release(key: ReserveKey): Boolean
}

/**
 * Универсальный распределённый замок с ожиданием и сроком жизни.
 */
interface LockManager {
    /**
     * @param waitMs  сколько ждать освобождения (0 — не ждать)
     * @param leaseMs сколько держать замок до авто-освобождения
     */
    suspend fun <T> withLock(key: String, waitMs: Long, leaseMs: Long, action: suspend () -> T): T
}

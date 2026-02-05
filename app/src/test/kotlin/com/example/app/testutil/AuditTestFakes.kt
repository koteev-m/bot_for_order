package com.example.app.testutil

import com.example.db.AuditLogRepository
import com.example.db.EventLogRepository
import com.example.db.IdempotencyRepository
import com.example.db.TelegramWebhookDedupAcquireResult
import com.example.db.TelegramWebhookDedupRepository
import com.example.domain.AuditLogEntry
import com.example.domain.EventLogEntry
import com.example.domain.IdempotencyKeyRecord
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryAuditLogRepository : AuditLogRepository {
    private val storage = mutableListOf<AuditLogEntry>()

    val entries: List<AuditLogEntry>
        get() = storage.toList()

    override suspend fun insert(entry: AuditLogEntry): Long {
        storage.add(entry)
        return storage.size.toLong()
    }
}

class InMemoryEventLogRepository : EventLogRepository {
    private val storage = mutableListOf<EventLogEntry>()

    val entries: List<EventLogEntry>
        get() = storage.toList()

    override suspend fun insert(entry: EventLogEntry): Long {
        storage.add(entry)
        return storage.size.toLong()
    }
}

class InMemoryIdempotencyRepository : IdempotencyRepository {
    private val storage = ConcurrentHashMap<String, IdempotencyKeyRecord>()

    override suspend fun findValid(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): IdempotencyKeyRecord? {
        val record = storage[recordKey(merchantId, userId, scope, key)] ?: return null
        return if (record.createdAt.isBefore(validAfter)) null else record
    }

    override suspend fun tryInsert(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        requestHash: String,
        createdAt: Instant
    ): Boolean {
        val mapKey = recordKey(merchantId, userId, scope, key)
        val record = IdempotencyKeyRecord(
            merchantId = merchantId,
            userId = userId,
            scope = scope,
            key = key,
            requestHash = requestHash,
            responseStatus = null,
            responseJson = null,
            createdAt = createdAt
        )
        return storage.putIfAbsent(mapKey, record) == null
    }

    override suspend fun updateResponse(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        responseStatus: Int,
        responseJson: String
    ) {
        val mapKey = recordKey(merchantId, userId, scope, key)
        val existing = storage[mapKey] ?: return
        storage[mapKey] = existing.copy(responseStatus = responseStatus, responseJson = responseJson)
    }

    override suspend fun delete(merchantId: String, userId: Long, scope: String, key: String) {
        storage.remove(recordKey(merchantId, userId, scope, key))
    }

    override suspend fun deleteIfExpired(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): Boolean {
        val mapKey = recordKey(merchantId, userId, scope, key)
        val record = storage[mapKey] ?: return false
        return if (record.createdAt.isBefore(validAfter)) {
            storage.remove(mapKey) != null
        } else {
            false
        }
    }

    private fun recordKey(merchantId: String, userId: Long, scope: String, key: String): String {
        return "$merchantId:$userId:$scope:$key"
    }
}

class InMemoryTelegramWebhookDedupRepository : TelegramWebhookDedupRepository {
    private data class Entry(
        val createdAt: Instant,
        val processedAt: Instant?
    )

    private val storage = ConcurrentHashMap<String, Entry>()

    override suspend fun tryAcquire(
        botType: String,
        updateId: Long,
        now: Instant,
        staleBefore: Instant
    ): TelegramWebhookDedupAcquireResult {
        val key = "$botType:$updateId"
        while (true) {
            val existing = storage[key]
            if (existing == null) {
                val inserted = storage.putIfAbsent(key, Entry(createdAt = now, processedAt = null))
                if (inserted == null) {
                    return TelegramWebhookDedupAcquireResult.ACQUIRED
                }
                continue
            }

            if (existing.processedAt != null) {
                return TelegramWebhookDedupAcquireResult.ALREADY_PROCESSED
            }

            if (existing.createdAt < staleBefore) {
                val replaced = storage.replace(key, existing, Entry(createdAt = now, processedAt = null))
                if (replaced) {
                    return TelegramWebhookDedupAcquireResult.ACQUIRED
                }
                continue
            }

            return TelegramWebhookDedupAcquireResult.IN_PROGRESS
        }
    }

    override suspend fun markProcessed(botType: String, updateId: Long, processedAt: Instant) {
        val key = "$botType:$updateId"
        storage.computeIfPresent(key) { _, entry ->
            if (entry.processedAt == null) entry.copy(processedAt = processedAt) else entry
        }
    }

    override suspend fun releaseProcessing(botType: String, updateId: Long) {
        val key = "$botType:$updateId"
        storage.computeIfPresent(key) { _, entry ->
            if (entry.processedAt == null) null else entry
        }
    }

    fun seedProcessing(botType: String, updateId: Long, createdAt: Instant) {
        storage["$botType:$updateId"] = Entry(createdAt = createdAt, processedAt = null)
    }
}

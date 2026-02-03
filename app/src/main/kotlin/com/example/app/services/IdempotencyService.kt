package com.example.app.services

import com.example.app.api.ApiError
import com.example.db.IdempotencyRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

class IdempotencyService(
    private val repository: IdempotencyRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofHours(24)
) {

    fun normalizeKey(raw: String?): String? {
        val key = raw?.trim() ?: return null
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) {
            throw ApiError("invalid_idempotency_key", HttpStatusCode.BadRequest)
        }
        return key
    }

    fun hashPayload(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun <T> execute(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        requestHash: String,
        block: suspend () -> IdempotentResponse<T>
    ): IdempotentOutcome<T> {
        val now = Instant.ofEpochMilli(clock.millis())
        val validAfter = now.minus(ttl)
        val existing = repository.findValid(merchantId, userId, scope, key, validAfter)
        if (existing != null) {
            return handleExisting(existing, requestHash)
        }

        var inserted = repository.tryInsert(merchantId, userId, scope, key, requestHash, now)
        if (!inserted) {
            val retry = repository.findValid(merchantId, userId, scope, key, validAfter)
            if (retry != null) {
                return handleExisting(retry, requestHash)
            } else {
                repository.deleteIfExpired(merchantId, userId, scope, key, validAfter)
                inserted = repository.tryInsert(merchantId, userId, scope, key, requestHash, now)
            }
            if (!inserted) {
                val lastCheck = repository.findValid(merchantId, userId, scope, key, validAfter)
                if (lastCheck != null) {
                    return handleExisting(lastCheck, requestHash)
                }
                throw ApiError("idempotency_in_progress", HttpStatusCode.Conflict)
            }
        }

        return try {
            val response = block()
            if (response.status.value in 200..299 && response.responseJson != null) {
                repository.updateResponse(
                    merchantId,
                    userId,
                    scope,
                    key,
                    response.status.value,
                    response.responseJson
                )
            } else {
                repository.delete(merchantId, userId, scope, key)
            }
            IdempotentOutcome.Executed(
                status = response.status,
                response = response.response,
                responseJson = response.responseJson
            )
        } catch (error: Exception) {
            repository.delete(merchantId, userId, scope, key)
            throw error
        }
    }

    private fun <T> handleExisting(
        existing: com.example.domain.IdempotencyKeyRecord,
        requestHash: String
    ): IdempotentOutcome<T> {
        if (existing.requestHash != requestHash) {
            throw ApiError("idempotency_key_conflict", HttpStatusCode.Conflict)
        }
        val status = existing.responseStatus
        val payload = existing.responseJson
        if (status != null && payload != null) {
            return IdempotentOutcome.Replay(status = HttpStatusCode.fromValue(status), responseJson = payload)
        }
        throw ApiError("idempotency_in_progress", HttpStatusCode.Conflict)
    }

    data class IdempotentResponse<T>(
        val status: HttpStatusCode,
        val response: T,
        val responseJson: String? = null,
        val contentType: ContentType = ContentType.Application.Json
    )

    sealed class IdempotentOutcome<out T> {
        data class Replay(val status: HttpStatusCode, val responseJson: String) : IdempotentOutcome<Nothing>()
        data class Executed<T>(
            val status: HttpStatusCode,
            val response: T,
            val responseJson: String?
        ) : IdempotentOutcome<T>()
    }

    companion object {
        private const val MAX_KEY_LENGTH = 128
    }
}

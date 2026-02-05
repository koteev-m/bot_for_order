package com.example.domain

import com.example.domain.serialization.InstantIsoSerializer
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class OutboxMessageStatus {
    NEW,
    PROCESSING,
    DONE,
    FAILED
}

@Serializable
data class OutboxMessage(
    val id: Long,
    val type: String,
    val payloadJson: String,
    val status: OutboxMessageStatus,
    val attempts: Int,
    @Serializable(with = InstantIsoSerializer::class)
    val nextAttemptAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    val lastError: String?
)

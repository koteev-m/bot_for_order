package com.example.app.routes

import com.example.db.TelegramWebhookDedupAcquireResult
import com.example.db.TelegramWebhookDedupRepository
import java.time.Duration
import java.time.Instant
import org.slf4j.Logger

internal const val TELEGRAM_BOT_TYPE_ADMIN = "admin"
internal const val TELEGRAM_BOT_TYPE_SHOP = "shop"
private val TELEGRAM_WEBHOOK_PROCESSING_TTL: Duration = Duration.ofMinutes(10)

enum class TelegramWebhookDedupDecision {
    ACQUIRED,
    ALREADY_PROCESSED,
    IN_PROGRESS
}

internal suspend fun acquireTelegramUpdateProcessing(
    dedupRepository: TelegramWebhookDedupRepository,
    botType: String,
    updateId: Long,
    logger: Logger,
    now: Instant = Instant.now(),
    processingTtl: Duration = TELEGRAM_WEBHOOK_PROCESSING_TTL
): TelegramWebhookDedupDecision {
    val staleBefore = now.minus(processingTtl)
    val result = dedupRepository.tryAcquire(
        botType = botType,
        updateId = updateId,
        now = now,
        staleBefore = staleBefore
    )

    return when (result) {
        TelegramWebhookDedupAcquireResult.ACQUIRED -> TelegramWebhookDedupDecision.ACQUIRED
        TelegramWebhookDedupAcquireResult.ALREADY_PROCESSED -> {
            logger.warn("tg_webhook_duplicate_dropped bot={} updateId={}", botType, updateId)
            TelegramWebhookDedupDecision.ALREADY_PROCESSED
        }
        TelegramWebhookDedupAcquireResult.IN_PROGRESS -> {
            logger.debug("tg_webhook_in_progress bot={} updateId={}", botType, updateId)
            TelegramWebhookDedupDecision.IN_PROGRESS
        }
    }
}

internal suspend fun markTelegramUpdateProcessedBestEffort(
    dedupRepository: TelegramWebhookDedupRepository,
    botType: String,
    updateId: Long,
    logger: Logger,
    processedAt: Instant = Instant.now()
) {
    runCatching {
        dedupRepository.markProcessed(
            botType = botType,
            updateId = updateId,
            processedAt = processedAt
        )
    }.onFailure { error ->
        logger.error("tg_webhook_mark_processed_failed bot={} updateId={}", botType, updateId, error)
    }
}

internal suspend fun releaseTelegramUpdateProcessingBestEffort(
    dedupRepository: TelegramWebhookDedupRepository,
    botType: String,
    updateId: Long,
    logger: Logger
) {
    runCatching {
        dedupRepository.releaseProcessing(botType = botType, updateId = updateId)
    }.onFailure { error ->
        logger.error("tg_webhook_release_processing_failed bot={} updateId={}", botType, updateId, error)
    }
}

package com.example.app.routes

import com.example.db.TelegramWebhookDedupRepository
import java.time.Instant
import org.slf4j.Logger

internal const val TELEGRAM_BOT_TYPE_ADMIN = "admin"
internal const val TELEGRAM_BOT_TYPE_SHOP = "shop"

internal suspend fun shouldProcessTelegramUpdate(
    dedupRepository: TelegramWebhookDedupRepository,
    botType: String,
    updateId: Long,
    logger: Logger
): Boolean {
    return try {
        val inserted = dedupRepository.tryMarkProcessed(botType, updateId, Instant.now())
        if (!inserted) {
            logger.warn("tg_webhook_duplicate_dropped bot={} updateId={}", botType, updateId)
        }
        inserted
    } catch (error: Exception) {
        // Fail-closed strategy: if dedup storage is unavailable, skip update processing to avoid
        // possible duplicate side-effects during Telegram retry deliveries.
        logger.error("tg_webhook_dedup_failed_closed bot={} updateId={}", botType, updateId, error)
        false
    }
}

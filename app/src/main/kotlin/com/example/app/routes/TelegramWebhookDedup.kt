package com.example.app.routes

import com.example.db.TelegramWebhookDedupAcquireResult
import com.example.db.TelegramWebhookDedupRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger

internal const val TELEGRAM_BOT_TYPE_ADMIN = "admin"
internal const val TELEGRAM_BOT_TYPE_SHOP = "shop"
private val TELEGRAM_WEBHOOK_PROCESSING_TTL: Duration = Duration.ofMinutes(10)
private val DUPLICATE_LOG_INFO_INTERVAL: Duration = Duration.ofMinutes(1)
private const val TELEGRAM_WEBHOOK_IN_PROGRESS_STATUS_ENV = "TELEGRAM_WEBHOOK_IN_PROGRESS_STATUS"
private const val TELEGRAM_WEBHOOK_IN_PROGRESS_RETRY_AFTER_SECONDS = "2"

private val duplicateInfoLogLimiter = DuplicateInfoLogLimiter()

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
            if (duplicateInfoLogLimiter.shouldLogInfo(botType, now)) {
                logger.info("tg_webhook_duplicate_dropped bot={} updateId={}", botType, updateId)
            } else {
                logger.debug("tg_webhook_duplicate_dropped bot={} updateId={}", botType, updateId)
            }
            TelegramWebhookDedupDecision.ALREADY_PROCESSED
        }
        TelegramWebhookDedupAcquireResult.IN_PROGRESS -> {
            logger.debug("tg_webhook_in_progress bot={} updateId={}", botType, updateId)
            TelegramWebhookDedupDecision.IN_PROGRESS
        }
    }
}

internal suspend fun ApplicationCall.respondTelegramInProgress() {
    response.header("Retry-After", TELEGRAM_WEBHOOK_IN_PROGRESS_RETRY_AFTER_SECONDS)
    respond(resolveTelegramInProgressStatus())
}

internal fun resolveTelegramInProgressStatus(env: (String) -> String? = System::getenv): HttpStatusCode {
    val raw = env(TELEGRAM_WEBHOOK_IN_PROGRESS_STATUS_ENV)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return HttpStatusCode.Conflict
    return when (raw) {
        "409" -> HttpStatusCode.Conflict
        "429" -> HttpStatusCode.TooManyRequests
        "503" -> HttpStatusCode.ServiceUnavailable
        else -> HttpStatusCode.Conflict
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

private class DuplicateInfoLogLimiter(
    private val interval: Duration = DUPLICATE_LOG_INFO_INTERVAL
) {
    private val lastLogByBotType = ConcurrentHashMap<String, AtomicReference<Instant>>()

    fun shouldLogInfo(botType: String, now: Instant): Boolean {
        val ref = lastLogByBotType.computeIfAbsent(botType) { AtomicReference(Instant.EPOCH) }
        while (true) {
            val previous = ref.get()
            if (now.isBefore(previous.plus(interval))) {
                return false
            }
            if (ref.compareAndSet(previous, now)) {
                return true
            }
        }
    }
}

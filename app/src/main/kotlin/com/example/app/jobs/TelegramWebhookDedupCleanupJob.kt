package com.example.app.jobs

import com.example.db.TelegramWebhookDedupRepository
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger

private const val DEDUP_RETENTION_DAYS_ENV = "TELEGRAM_WEBHOOK_DEDUP_RETENTION_DAYS"
private const val DEDUP_CLEANUP_INTERVAL_MINUTES_ENV = "TELEGRAM_WEBHOOK_DEDUP_CLEANUP_INTERVAL_MINUTES"
private const val DEFAULT_RETENTION_DAYS = 30L
private const val MIN_RETENTION_DAYS = 1L
private const val MAX_RETENTION_DAYS = 365L
private const val DEFAULT_INTERVAL_MINUTES = 60L
private const val MIN_INTERVAL_MINUTES = 5L
private const val MAX_INTERVAL_MINUTES = 1_440L

internal data class TelegramWebhookDedupCleanupConfig(
    val retention: Duration,
    val interval: Duration
)

internal fun telegramWebhookDedupCleanupConfig(env: (String) -> String? = System::getenv): TelegramWebhookDedupCleanupConfig {
    val retentionDays = parseBoundedLongEnv(
        env = env,
        name = DEDUP_RETENTION_DAYS_ENV,
        defaultValue = DEFAULT_RETENTION_DAYS,
        min = MIN_RETENTION_DAYS,
        max = MAX_RETENTION_DAYS
    )
    val intervalMinutes = parseBoundedLongEnv(
        env = env,
        name = DEDUP_CLEANUP_INTERVAL_MINUTES_ENV,
        defaultValue = DEFAULT_INTERVAL_MINUTES,
        min = MIN_INTERVAL_MINUTES,
        max = MAX_INTERVAL_MINUTES
    )
    return TelegramWebhookDedupCleanupConfig(
        retention = Duration.ofDays(retentionDays),
        interval = Duration.ofMinutes(intervalMinutes)
    )
}

internal fun Application.installTelegramWebhookDedupCleanup(
    dedupRepository: TelegramWebhookDedupRepository,
    config: TelegramWebhookDedupCleanupConfig = telegramWebhookDedupCleanupConfig(),
    clock: Clock = Clock.systemUTC(),
    logger: Logger = environment.log
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("TelegramWebhookDedupCleanupJob"))

    monitor.subscribe(ApplicationStarted) {
        scope.launch {
            while (isActive) {
                runCatching {
                    val now = clock.instant()
                    val processedBefore = now.minus(config.retention)
                    val staleProcessingBefore = now.minus(config.retention)
                    val deleted = dedupRepository.purge(processedBefore, staleProcessingBefore)
                    logger.info("tg_webhook_dedup_cleanup deletedCount={} retentionDays={}", deleted, config.retention.toDays())
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    logger.warn("tg_webhook_dedup_cleanup_failed", error)
                }
                delay(config.interval.toMillis())
            }
        }
    }

    monitor.subscribe(ApplicationStopping) {
        scope.cancel()
    }
}

private fun parseBoundedLongEnv(
    env: (String) -> String?,
    name: String,
    defaultValue: Long,
    min: Long,
    max: Long
): Long {
    val raw = env(name)?.trim()?.takeIf(String::isNotEmpty) ?: return defaultValue
    val parsed = raw.toLongOrNull() ?: return defaultValue
    return parsed.coerceIn(min, max)
}

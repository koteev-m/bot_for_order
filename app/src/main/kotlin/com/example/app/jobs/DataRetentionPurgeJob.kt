package com.example.app.jobs

import com.example.app.config.AppConfig
import com.example.db.DataRetentionRepository
import com.example.domain.OrderStatus
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

internal data class PurgeCutoffs(
    val auditLogBefore: Instant,
    val orderDeliveryBefore: Instant,
    val outboxBefore: Instant,
    val webhookDedupBefore: Instant,
    val idempotencyBefore: Instant,
)

internal object DataRetentionPolicy {
    val completedOrderStatuses: List<OrderStatus> = listOf(
        OrderStatus.delivered,
        OrderStatus.canceled,
        OrderStatus.PAID_CONFIRMED,
    )

    fun buildCutoffs(config: AppConfig, now: Instant): PurgeCutoffs = PurgeCutoffs(
        auditLogBefore = now.minus(Duration.ofDays(config.retention.pii.auditLogDays)),
        orderDeliveryBefore = now.minus(Duration.ofDays(config.retention.pii.orderDeliveryDays)),
        outboxBefore = now.minus(Duration.ofDays(config.retention.technical.outboxDays)),
        webhookDedupBefore = now.minus(Duration.ofDays(config.retention.technical.webhookDedupDays)),
        idempotencyBefore = now.minus(Duration.ofDays(config.retention.technical.idempotencyDays)),
    )
}

class DataRetentionPurgeJob(
    private val application: Application,
    private val repository: DataRetentionRepository,
    private val config: AppConfig,
    private val clock: Clock = Clock.systemUTC(),
    meterRegistry: MeterRegistry? = null,
) {
    private val log = LoggerFactory.getLogger(DataRetentionPurgeJob::class.java)
    private val successCounter = meterRegistry?.counter("retention_purge_runs_total", "result", "success")
    private val errorCounter = meterRegistry?.counter("retention_purge_runs_total", "result", "error")

    fun start() {
        if (!config.retention.purgeEnabled) {
            log.info("retention_purge_disabled")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("DataRetentionPurgeJob"))
        application.environment.monitor.subscribe(ApplicationStopped) { scope.cancel() }
        val intervalMs = Duration.ofHours(config.retention.intervalHours).toMillis()
        scope.launch {
            while (isActive) {
                runCatching { runOnce() }
                    .onSuccess { successCounter?.increment() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        errorCounter?.increment()
                        log.warn("retention_purge_failed cause={}", error.message, error)
                    }
                delay(intervalMs)
            }
        }
    }

    suspend fun runOnce() {
        val now = clock.instant()
        val cutoffs = DataRetentionPolicy.buildCutoffs(config, now)
        val completedStatuses = DataRetentionPolicy.completedOrderStatuses
        val auditAnonymized = repository.anonymizeAuditLog(cutoffs.auditLogBefore)
        val ordersAddressAnonymized = repository.anonymizeOrderAddressForCompleted(cutoffs.orderDeliveryBefore, completedStatuses)
        val orderDeliveryAnonymized = repository.anonymizeOrderDeliveryForCompleted(cutoffs.orderDeliveryBefore, completedStatuses)
        val outboxDeleted = repository.purgeOutbox(cutoffs.outboxBefore)
        val webhookDedupDeleted = repository.purgeWebhookDedup(
            processedBefore = cutoffs.webhookDedupBefore,
            staleProcessingBefore = cutoffs.webhookDedupBefore,
        )
        val idempotencyDeleted = repository.purgeIdempotency(cutoffs.idempotencyBefore)
        log.info(
            "retention_purge_done audit_anonymized={} order_address_anonymized={} order_delivery_anonymized={} outbox_deleted={} webhook_dedup_deleted={} idempotency_deleted={}",
            auditAnonymized,
            ordersAddressAnonymized,
            orderDeliveryAnonymized,
            outboxDeleted,
            webhookDedupDeleted,
            idempotencyDeleted,
        )
    }
}

fun Application.installDataRetentionPurgeJob(cfg: AppConfig) {
    val dataRetentionRepository by inject<DataRetentionRepository>()
    val meterRegistry = runCatching { inject<MeterRegistry>().value }.getOrNull()
    DataRetentionPurgeJob(
        application = this,
        repository = dataRetentionRepository,
        config = cfg,
        meterRegistry = meterRegistry,
    ).start()
}

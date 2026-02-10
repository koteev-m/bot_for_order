package com.example.app.services

import com.example.app.config.AppConfig
import com.example.db.OutboxRepository
import com.example.domain.OutboxMessage
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.random.Random
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

fun interface OutboxHandler {
    suspend fun handle(payloadJson: String)
}

class OutboxHandlerRegistry(private val handlers: Map<String, OutboxHandler>) {
    fun find(type: String): OutboxHandler? = handlers[type]
}

class OutboxWorker(
    private val application: Application,
    private val outboxRepository: OutboxRepository,
    private val handlerRegistry: OutboxHandlerRegistry,
    private val config: AppConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
    meterRegistry: MeterRegistry? = null
) {
    private val log = LoggerFactory.getLogger(OutboxWorker::class.java)
    private val doneCounter = meterRegistry?.counter("outbox_processed_total", "result", "done")
    private val failedCounter = meterRegistry?.counter("outbox_processed_total", "result", "failed")
    private val retriedCounter = meterRegistry?.counter("outbox_retries_total")
    private val backlogGauge = AtomicLong(0)

    init {
        meterRegistry?.gauge("outbox_backlog_due", backlogGauge)
    }

    fun start() {
        if (!config.outbox.enabled) {
            log.info("outbox_worker_disabled")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("OutboxWorker"))
        application.environment.monitor.subscribe(ApplicationStopped) { scope.cancel() }
        scope.launch {
            while (isActive) {
                runCatching { runOnce() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.warn("outbox_batch_failed cause={}", error.message, error)
                    }
                delay(config.outbox.pollIntervalMs)
            }
        }
    }

    suspend fun runOnce() {
        val now = clock.instant()
        val processingLeaseUntil = now.plus(Duration.ofMillis(config.outbox.processingTtlMs))
        val due = outboxRepository.fetchDueBatch(config.outbox.batchSize, now, processingLeaseUntil)
        if (due.isEmpty()) {
            return
        }
        val backlog = outboxRepository.countBacklog(now)
        backlogGauge.set(backlog)
        log.info("outbox_batch_picked size={} backlog={}", due.size, backlog)
        due.forEach { message ->
            val handler = handlerRegistry.find(message.type)
            if (handler == null) {
                outboxRepository.markFailed(message.id, trimError("no handler for type=${message.type}"))
                failedCounter?.increment()
                log.error("outbox_message_failed id={} type={} reason=no_handler", message.id, message.type)
                return@forEach
            }
            runCatching {
                handler.handle(message.payloadJson)
                outboxRepository.markDone(message.id)
                doneCounter?.increment()
                log.info("outbox_message_done id={} type={} attempts={}", message.id, message.type, message.attempts)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                onFailure(message, error)
            }
        }
    }

    private suspend fun onFailure(message: OutboxMessage, error: Throwable) {
        val errorText = trimError(error.message ?: error.javaClass.simpleName)
        if (message.attempts >= config.outbox.maxAttempts) {
            outboxRepository.markFailed(message.id, errorText)
            failedCounter?.increment()
            log.error(
                "outbox_message_failed id={} type={} attempts={} reason=max_attempts",
                message.id,
                message.type,
                message.attempts
            )
            return
        }

        val nextAttemptAt = clock.instant().plusMillis(computeBackoffMs(message.attempts))
        outboxRepository.reschedule(
            id = message.id,
            attempts = message.attempts,
            nextAttemptAt = nextAttemptAt,
            lastError = errorText
        )
        retriedCounter?.increment()
        log.warn(
            "outbox_message_retry id={} type={} attempts={} next_attempt_at={}",
            message.id,
            message.type,
            message.attempts,
            nextAttemptAt
        )
    }

    private fun computeBackoffMs(attempts: Int): Long {
        val cappedShift = (attempts - 1).coerceAtLeast(0).coerceAtMost(30)
        val exponential = config.outbox.baseBackoffMs * (1L shl cappedShift)
        val bounded = min(exponential, config.outbox.maxBackoffMs)
        val jitter = (bounded * 0.2).toLong().coerceAtLeast(1)
        val randomJitter = random.nextLong(from = 0, until = jitter + 1)
        return (bounded + randomJitter).coerceAtMost(config.outbox.maxBackoffMs)
    }

    private fun trimError(value: String): String {
        val maxLength = 512
        return if (value.length <= maxLength) value else value.take(maxLength)
    }
}

fun Application.installOutboxWorker(cfg: AppConfig) {
    val outboxRepository by inject<OutboxRepository>()
    val meterRegistry = runCatching { inject<MeterRegistry>().value }.getOrNull()
    val handlerRegistry = OutboxHandlerRegistry(
        handlers = mapOf(
            "noop.test" to OutboxHandler { _ -> Unit }
        )
    )
    OutboxWorker(
        application = this,
        outboxRepository = outboxRepository,
        handlerRegistry = handlerRegistry,
        config = cfg,
        meterRegistry = meterRegistry
    ).start()
}

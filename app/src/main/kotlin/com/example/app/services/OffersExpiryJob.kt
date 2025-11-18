package com.example.app.services

import com.example.app.config.AppConfig
import com.example.db.OffersRepository
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import java.time.Clock
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class OffersExpiryJob(
    private val application: Application,
    private val offersRepository: OffersRepository,
    private val sweepIntervalSec: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val log: Logger = LoggerFactory.getLogger(OffersExpiryJob::class.java)
) {

    fun start() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("OffersExpiryJob"))
        application.environment.monitor.subscribe(ApplicationStopped) { scope.cancel() }
        val intervalMs = sweepIntervalSec.coerceAtLeast(1).toLong() * 1_000L
        scope.launch {
            while (isActive) {
                runCatching {
                    val now = clock.instant()
                    val expired = offersRepository.expireWhereDue(now)
                    if (expired > 0) {
                        log.info("offer_expiry_sweep expired={}", expired)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.warn("offer_expiry_sweep_failed: ${error.message}", error)
                }
                delay(intervalMs)
            }
        }
    }
}

fun Application.installOffersExpiryJob(cfg: AppConfig) {
    val offersRepository by inject<OffersRepository>()
    OffersExpiryJob(
        this,
        offersRepository,
        cfg.server.offersExpireSweepSec,
        log = environment.log
    ).start()
}

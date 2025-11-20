package com.example.app.services

import com.example.app.config.AppConfig
import com.example.db.VariantsRepository
import com.example.domain.watchlist.WatchlistRepository
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
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

class RestockScannerJob(
    private val application: Application,
    private val watchlistRepository: WatchlistRepository,
    private val variantsRepository: VariantsRepository,
    private val restockAlertService: RestockAlertService,
    private val scanIntervalSec: Int
) {

    private val log = LoggerFactory.getLogger(RestockScannerJob::class.java)

    fun start() {
        val intervalMs = scanIntervalSec.coerceAtLeast(5).toLong() * 1_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(JOB_NAME))
        application.environment.monitor.subscribe(ApplicationStopped) { scope.cancel() }
        scope.launch {
            while (isActive) {
                runCatching { scanOnce() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.warn("restock_scan_failed cause={}", error.message, error)
                    }
                delay(intervalMs)
            }
        }
    }

    private suspend fun scanOnce() {
        val subscriptions = watchlistRepository.listRestockSubscriptions()
        if (subscriptions.isEmpty()) {
            return
        }
        val grouped = subscriptions.groupBy { it.itemId to it.variantId }
        grouped.forEach { (key, _) ->
            val (itemId, variantId) = key
            if (hasPositiveStock(itemId, variantId)) {
                val dispatched = restockAlertService.dispatch(itemId, variantId)
                if (dispatched > 0) {
                    log.info(
                        "restock_scan_dispatch item={} variant={} targets={}",
                        itemId,
                        variantId ?: "_",
                        dispatched
                    )
                }
            }
        }
    }

    private suspend fun hasPositiveStock(itemId: String, variantId: String?): Boolean {
        return if (variantId != null) {
            variantsRepository.getById(variantId)?.let { it.active && it.stock > 0 } == true
        } else {
            variantsRepository.listByItem(itemId).any { it.active && it.stock > 0 }
        }
    }

    private companion object {
        private const val JOB_NAME = "RestockScannerJob"
    }
}

fun Application.installRestockScannerJob(cfg: AppConfig) {
    if (!cfg.server.watchlistRestockEnabled) {
        return
    }
    val watchlistRepository by inject<WatchlistRepository>()
    val variantsRepository by inject<VariantsRepository>()
    val restockAlertService by inject<RestockAlertService>()
    RestockScannerJob(
        this,
        watchlistRepository,
        variantsRepository,
        restockAlertService,
        cfg.server.restockScanSec
    ).start()
}

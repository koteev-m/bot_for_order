package com.example.app.services

import com.example.app.config.AppConfig
import com.example.domain.DisplayPriceService
import com.example.domain.FxService
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Suppress("TooGenericExceptionCaught")
class FxRefresher(
    private val application: Application,
    private val cfg: AppConfig,
    private val fxService: FxService,
    private val displayPriceService: DisplayPriceService,
    private val log: Logger = LoggerFactory.getLogger(FxRefresher::class.java)
) {

    fun start() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("FxRefresher"))
        application.environment.monitor.subscribe(ApplicationStopped) { scope.cancel() }
        val intervalMs = cfg.fx.refreshIntervalSec.coerceAtLeast(1).toLong() * 1000L
        scope.launch {
            while (isActive) {
                try {
                    val snapshot = fxService.refresh()
                    displayPriceService.recomputeAllActive()
                    log.info(
                        "FX snapshot refreshed. ts={} currencies={}",
                        snapshot.ts,
                        snapshot.rates.keys.sorted().joinToString(",")
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("FX refresh failed: ${'$'}{e.message}", e)
                }
                delay(intervalMs)
            }
        }
    }
}

fun Application.installFxRefresher(cfg: AppConfig) {
    val fxService by inject<FxService>()
    val displayPriceService by inject<DisplayPriceService>()
    FxRefresher(this, cfg, fxService, displayPriceService, environment.log).start()
}

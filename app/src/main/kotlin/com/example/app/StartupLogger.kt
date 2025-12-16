package com.example.app

import com.example.app.config.AppConfig
import org.slf4j.Logger

fun logStartup(log: Logger, cfg: AppConfig) {
    log.info(
        "Application started. baseUrl={}, currency={}, admins={}, metricsEnabled={}, prometheusEnabled={}",
        cfg.server.publicBaseUrl,
        cfg.payments.invoiceCurrency,
        cfg.telegram.adminIds.size,
        cfg.metrics.enabled,
        cfg.metrics.prometheusEnabled
    )
}

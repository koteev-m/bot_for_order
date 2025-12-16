package com.example.app

import com.example.app.api.installApiErrors
import com.example.app.api.respondApiError
import com.example.app.config.AppConfig
import com.example.app.config.ConfigLoader
import com.example.app.di.adminModule
import com.example.app.di.appModule
import com.example.app.di.dbModule
import com.example.app.di.fxModule
import com.example.app.di.offersModule
import com.example.app.di.paymentsModule
import com.example.app.di.redisBindingsModule
import com.example.app.observability.registerBuildInfoMeter
import com.example.app.observability.REQUEST_ID_MDC_KEY
import com.example.app.observability.USER_ID_MDC_KEY
import com.example.app.routes.installAdminWebhook
import com.example.app.routes.installApiRoutes
import com.example.app.routes.installBaseRoutes
import com.example.app.routes.installShopWebhook
import com.example.app.routes.installStaticAppRoutes
import com.example.app.security.InitDataAuth
import com.example.app.services.installFxRefresher
import com.example.app.services.installOffersExpiryJob
import com.example.app.services.installReservesSweepJob
import com.example.app.services.installRestockScannerJob
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.UUID
import org.flywaydb.core.Flyway
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun main() {
    EngineMain.main(emptyArray())
}

@Suppress("unused")
fun Application.module() {
    val log = LoggerFactory.getLogger("Boot")

    val cfg = loadConfiguration(log)
    val meterRegistry = configureMetrics(cfg)
    configureDependencyInjection(cfg, meterRegistry)
    runMigrations(log)
    installFxRefresher(cfg)
    installOffersExpiryJob(cfg)
    installReservesSweepJob(cfg)
    installRestockScannerJob(cfg)
    configureServerPlugins()

    installAdminWebhook()
    installShopWebhook()
    installApiRoutes()
    installStaticAppRoutes()
    installBaseRoutes(cfg, meterRegistry)

    logStartup(log, cfg)
}

private fun loadConfiguration(log: Logger): AppConfig {
    return try {
        ConfigLoader.fromEnv()
    } catch (e: IllegalStateException) {
        log.error("ENV configuration error: ${e.message}")
        throw e
    } catch (e: IllegalArgumentException) {
        log.error("ENV configuration error: ${e.message}")
        throw e
    }
}

private fun Application.configureDependencyInjection(cfg: AppConfig, meterRegistry: MeterRegistry?) {
    install(Koin) {
        modules(
            appModule(cfg, meterRegistry),
            dbModule(cfg),
            redisBindingsModule,
            adminModule,
            fxModule(cfg),
            paymentsModule,
            offersModule
        )
    }
}

private fun Application.runMigrations(log: Logger) {
    val flyway by inject<Flyway>()
    val migrations = flyway.migrate()
    log.info(
        "Flyway migrated: initial={} current={}",
        migrations.initialSchemaVersion,
        migrations.targetSchemaVersion
    )
}

private fun Application.configureServerPlugins() {
    installCallIdPlugin()
    installCallLoggingPlugin()
    installStatusPageHandlers()
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            }
        )
    }
}

private fun Application.installCallIdPlugin() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader(HttpHeaders.XRequestId)
    }
}

private fun Application.installCallLoggingPlugin() {
    install(CallLogging) {
        level = Level.INFO
        callIdMdc(REQUEST_ID_MDC_KEY)
        mdc(USER_ID_MDC_KEY) { call ->
            if (call.attributes.contains(InitDataAuth.VERIFIED_USER_ATTR)) {
                call.attributes[InitDataAuth.VERIFIED_USER_ATTR].toString()
            } else {
                null
            }
        }
        filter { call ->
            val path = call.request.path()
            !path.startsWith("/health") && !path.startsWith("/metrics")
        }
    }
}

private fun Application.installStatusPageHandlers() {
    val appLog = environment.log
    install(StatusPages) {
        installApiErrors(appLog)
        exception<BadRequestException> { call, cause ->
            call.respondApiError(
                appLog,
                HttpStatusCode.BadRequest,
                cause.message ?: "bad_request",
                warn = true,
                cause = cause
            )
        }
        exception<NotFoundException> { call, cause ->
            call.respondApiError(
                appLog,
                HttpStatusCode.NotFound,
                cause.message ?: "not_found",
                warn = true,
                cause = cause
            )
        }
        status(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound,
            HttpStatusCode.UnprocessableEntity
        ) { call, status ->
            call.respondApiError(
                appLog,
                status,
                status.description.lowercase().replace(" ", "_"),
                warn = status.value < 500
            )
        }
        exception<Throwable> { call, cause ->
            call.respondApiError(
                appLog,
                HttpStatusCode.InternalServerError,
                "internal_error",
                warn = false,
                cause = cause
            )
        }
    }
}

private fun Application.configureMetrics(cfg: AppConfig): MeterRegistry? {
    if (!cfg.metrics.enabled) return null

    val registry: MeterRegistry = if (cfg.metrics.prometheusEnabled) {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    } else {
        SimpleMeterRegistry()
    }
    registry.config().commonTags("service", "tg-shop")

    install(MicrometerMetrics) {
        metricName = "http.server.requests"
        this.registry = registry
        distributionStatisticConfig = DistributionStatisticConfig
            .builder()
            .serviceLevelObjectives(
                Duration.ofMillis(100).toNanos().toDouble(),
                Duration.ofMillis(500).toNanos().toDouble()
            )
            .maximumExpectedValue(Duration.ofSeconds(20).toNanos().toDouble())
            .percentilesHistogram(true)
            .build()
        meterBinders = listOf(
            JvmGcMetrics(),
            JvmMemoryMetrics(),
            ProcessorMetrics()
        )
    }

    registerBuildInfoMeter(registry)

    return registry
}

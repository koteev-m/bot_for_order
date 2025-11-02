package com.example.app

import com.example.app.api.installApiErrors
import com.example.app.config.AppConfig
import com.example.app.config.ConfigLoader
import com.example.app.di.adminModule
import com.example.app.di.appModule
import com.example.app.di.dbModule
import com.example.app.di.redisBindingsModule
import com.example.app.routes.installAdminWebhook
import com.example.app.routes.installApiRoutes
import com.example.app.routes.installShopWebhook
import com.example.app.routes.installStaticAppRoutes
import com.example.app.security.RbacActorMdcPlugin
import com.example.app.security.UserPrincipalPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI
import org.flywaydb.core.Flyway
import java.util.concurrent.ThreadLocalRandom
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun main() = EngineMain.main(emptyArray())

@Suppress("unused")
fun Application.module() {
    val log = LoggerFactory.getLogger("Boot")

    val cfg = loadConfiguration(log)
    configureDependencyInjection(cfg)
    runMigrations(log)
    configureServerPlugins()

    installAdminWebhook()
    installShopWebhook()
    installApiRoutes()
    installStaticAppRoutes()
    installBaseRoutes(cfg)

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

private fun Application.configureDependencyInjection(cfg: AppConfig) {
    install(Koin) {
        modules(appModule(cfg), dbModule(cfg), redisBindingsModule, adminModule)
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
    install(CallId) {
        retrieveFromHeader("X-Request-Id")
        generate { generateRequestId() }
        verify { id -> id.isNotBlank() && id.matches(Regex("[A-Za-z0-9._-]+")) }
        reply { call, callId ->
            call.response.headers.append("X-Request-Id", callId)
        }
    }
    install(CallLogging) {
        callIdMdc("request_id")
    }
    install(UserPrincipalPlugin)
    install(RbacActorMdcPlugin)
    install(StatusPages) {
        installApiErrors()
        exception<Throwable> { call, cause ->
            call.respondText("Internal error", status = HttpStatusCode.InternalServerError)
            this@configureServerPlugins.environment.log.error("Unhandled", cause)
        }
    }
    install(ContentNegotiation) { json() }
}

private fun Application.installBaseRoutes(cfg: AppConfig) {
    routing {
        get("/health") { call.respondText("OK") }
        get("/") { call.respondText("tg-shop-monorepo up") }
        get("/_diag") {
            val maskedRedis = maskRedisUrl(cfg)
            val masked = buildString {
                append("admins=")
                append(cfg.telegram.adminIds.size)
                append(", channelId=")
                append(cfg.telegram.channelId)
                append(", redis=")
                append(maskedRedis)
                append(", currency=")
                append(cfg.payments.invoiceCurrency)
                append(", baseUrl=")
                append(cfg.server.publicBaseUrl)
            }
            call.respondText("cfg:$masked")
        }
    }
}

private fun maskRedisUrl(cfg: AppConfig): String {
    return runCatching { URI(cfg.redis.url) }
        .map { uri ->
            buildString {
                append(uri.scheme)
                append("://")
                append(uri.host ?: uri.authority ?: "unknown")
                if (uri.port != -1) {
                    append(":${uri.port}")
                }
            }
        }
        .getOrElse { "redis://invalid" }
}

private fun logStartup(log: Logger, cfg: AppConfig) {
    log.info(
        "Application started. baseUrl={}, currency={}, admins={}",
        cfg.server.publicBaseUrl,
        cfg.payments.invoiceCurrency,
        cfg.telegram.adminIds.size
    )
}

private fun generateRequestId(): String {
    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    val random = ThreadLocalRandom.current()
    return CharArray(20) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
}

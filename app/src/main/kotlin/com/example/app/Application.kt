package com.example.app

import com.example.app.config.AppConfig
import com.example.app.config.ConfigLoader
import com.example.app.di.appModule
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI
import org.koin.ktor.plugin.Koin
import org.koin.ktor.plugin.slf4jLogger
import org.slf4j.LoggerFactory

fun main() = EngineMain.main(emptyArray())

@Suppress("unused")
fun Application.module() {
    val log = LoggerFactory.getLogger("Boot")

    val cfg = try {
        ConfigLoader.fromEnv()
    } catch (e: IllegalStateException) {
        log.error("ENV configuration error: ${e.message}")
        throw e
    } catch (e: IllegalArgumentException) {
        log.error("ENV configuration error: ${e.message}")
        throw e
    }

    install(Koin) {
        slf4jLogger()
        modules(appModule(cfg))
    }

    install(CallLogging)
    configureStatusPages()
    install(ContentNegotiation) { json() }

    configureRouting(cfg)

    log.info(
        "Application started. baseUrl={}, currency={}, admins={}",
        cfg.server.publicBaseUrl,
        cfg.payments.invoiceCurrency,
        cfg.telegram.adminIds.size
    )
}

private fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText("Internal error", status = HttpStatusCode.InternalServerError)
            environment.log.error("Unhandled", cause)
        }
    }
}

private fun Application.configureRouting(cfg: AppConfig) {
    routing {
        get("/health") { call.respondText("OK") }
        get("/") { call.respondText("tg-shop-monorepo up") }
        get("/_diag") {
            val maskedRedis = runCatching { URI(cfg.redis.url) }
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

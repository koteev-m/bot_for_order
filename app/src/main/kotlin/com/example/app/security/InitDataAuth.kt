package com.example.app.security

import com.example.app.api.ApiError
import com.example.app.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.util.AttributeKey
import java.time.Duration

object InitDataAuth {
    val VERIFIED_USER_ATTR: AttributeKey<Long> = AttributeKey("verifiedUserId")
}

/**
 * Route-scoped Ktor plugin, validates X-Telegram-Init-Data and exposes verifiedUserId attribute.
 */
fun Route.installInitDataAuth(appConfig: AppConfig) {
    val botToken = appConfig.telegram.shopToken
    val maxAgeSec = Duration.ofHours(24).seconds
    val optionalWhenMissing = (System.getenv("ALLOW_INSECURE_INITDATA") ?: "false").equals("true", ignoreCase = true)

    this.intercept(ApplicationCallPipeline.Plugins) {
        val initData = call.request.headers["X-Telegram-Init-Data"]
        if (initData.isNullOrBlank()) {
            if (optionalWhenMissing) {
                return@intercept
            }
            unauthorized("initData_required")
        }

        val verified = try {
            TelegramInitDataVerifier.verify(initData, botToken, maxAgeSec)
        } catch (e: IllegalArgumentException) {
            unauthorized("invalid_initData", e)
        } catch (e: IllegalStateException) {
            unauthorized("invalid_initData", e)
        }

        call.attributes.put(InitDataAuth.VERIFIED_USER_ATTR, verified.userId)
    }
}

/** Helper: prefer verified userId, fallback to legacy X-User-Id only if present (dev). */
fun ApplicationCall.requireUserId(): Long {
    if (this.attributes.contains(InitDataAuth.VERIFIED_USER_ATTR)) {
        return this.attributes[InitDataAuth.VERIFIED_USER_ATTR]
    }
    val legacy = this.request.headers["X-User-Id"]?.toLongOrNull()
    if (legacy != null) return legacy
    throw ApiError("unauthorized", HttpStatusCode.Unauthorized)
}

private fun unauthorized(reason: String, cause: Throwable? = null): Nothing =
    throw ApiError(reason, HttpStatusCode.Unauthorized, cause)

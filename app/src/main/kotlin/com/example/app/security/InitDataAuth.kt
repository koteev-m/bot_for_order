package com.example.app.security

import com.example.app.api.ApiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.util.AttributeKey

object InitDataAuth {
    val VERIFIED_USER_ATTR: AttributeKey<Long> = AttributeKey("verifiedUserId")
}

/**
 * Route-scoped Ktor plugin, validates X-Telegram-Init-Data and exposes verifiedUserId attribute.
 */
fun Route.installInitDataAuth(verifier: TelegramInitDataVerifier) {
    this.intercept(ApplicationCallPipeline.Plugins) {
        val initData = call.request.headers["X-Telegram-Init-Data"]
            ?: call.request.headers["X-Init-Data"]
        if (initData.isNullOrBlank()) {
            unauthorized("initData_required")
        }

        val verified = try {
            verifier.verify(initData)
        } catch (e: IllegalArgumentException) {
            unauthorized("invalid_initData", e)
        } catch (e: IllegalStateException) {
            unauthorized("invalid_initData", e)
        }

        call.attributes.put(InitDataAuth.VERIFIED_USER_ATTR, verified.userId)
    }
}

fun ApplicationCall.requireUserId(): Long {
    if (this.attributes.contains(InitDataAuth.VERIFIED_USER_ATTR)) {
        return this.attributes[InitDataAuth.VERIFIED_USER_ATTR]
    }
    throw ApiError("unauthorized", HttpStatusCode.Unauthorized)
}

private fun unauthorized(reason: String, cause: Throwable? = null): Nothing =
    throw ApiError(reason, HttpStatusCode.Unauthorized, cause)

package com.example.app.api

import com.example.app.observability.requestId
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.slf4j.Logger

class ApiError(
    message: String,
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
    cause: Throwable? = null
) : RuntimeException(message, cause)

@Serializable
data class ErrorResponse(
    val error: String?,
    val requestId: String?,
)

fun StatusPagesConfig.installApiErrors(logger: Logger) {
    exception<ApiError> { call, e ->
        call.respondApiError(logger, e.status, e.message, warn = e.status.value < 500, cause = e)
    }
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondApiError(
    logger: Logger,
    status: HttpStatusCode,
    message: String?,
    warn: Boolean,
    cause: Throwable? = null,
) {
    val payload = ErrorResponse(message, requestId())
    if (warn) {
        logger.warn(
            "Request failed: status={} error={} requestId={}",
            status.value,
            message,
            payload.requestId,
            cause
        )
    } else {
        logger.error(
            "Request failed: status={} error={} requestId={}",
            status.value,
            message,
            payload.requestId,
            cause
        )
    }
    respond(status, payload)
}

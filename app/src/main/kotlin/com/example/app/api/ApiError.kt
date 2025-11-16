package com.example.app.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond

class ApiError(
    message: String,
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
    cause: Throwable? = null
) : RuntimeException(message, cause)

fun StatusPagesConfig.installApiErrors() {
    exception<ApiError> { call, e ->
        call.respond(e.status, mapOf("error" to e.message))
    }
}

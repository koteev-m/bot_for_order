package com.example.app.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Временная аутентификация: читаем userId из заголовка X-User-Id.
 * В S12 заменим на валидацию initData Telegram WebApp.
 */
fun ApplicationCall.requireUserId(): Long {
    val raw = this.request.headers["X-User-Id"] ?: throw ApiError("X-User-Id header required")
    return raw.toLongOrNull() ?: throw ApiError("X-User-Id must be a number")
}

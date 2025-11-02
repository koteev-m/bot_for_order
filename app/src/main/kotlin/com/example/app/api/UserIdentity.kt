package com.example.app.api

import com.example.app.security.UserPrincipal
import com.example.app.security.rbacPrincipal
import com.example.app.security.setRbacPrincipal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Временная аутентификация: читаем userId из заголовка X-User-Id.
 * В S12 заменим на валидацию initData Telegram WebApp.
 */
fun ApplicationCall.requireUserId(): Long {
    val contextPrincipal = this.rbacPrincipal
    if (contextPrincipal is UserPrincipal) {
        return contextPrincipal.userId
    }
    val raw = this.request.headers["X-User-Id"] ?: throw ApiError("X-User-Id header required")
    val userId = raw.toLongOrNull() ?: throw ApiError("X-User-Id must be a number")
    setRbacPrincipal(UserPrincipal(userId))
    return userId
}

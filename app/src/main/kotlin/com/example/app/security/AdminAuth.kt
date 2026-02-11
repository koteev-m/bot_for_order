package com.example.app.security

import com.example.app.api.ApiError
import com.example.app.config.AppConfig
import com.example.db.AdminUsersRepository
import com.example.domain.AdminRole
import com.example.domain.AdminUser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

suspend fun ApplicationCall.requireAdminUser(
    config: AppConfig,
    adminUsersRepository: AdminUsersRepository,
    allowedRoles: Set<AdminRole>
): AdminUser {
    val userId = requireUserId()
    val merchantId = config.merchants.defaultMerchantId
    val admin = adminUsersRepository.get(merchantId, userId)
        ?: throw ApiError("forbidden", HttpStatusCode.Forbidden)
    if (!isRoleAllowed(admin.role, allowedRoles)) {
        throw ApiError("forbidden", HttpStatusCode.Forbidden)
    }
    return admin
}

private fun isRoleAllowed(role: AdminRole, allowedRoles: Set<AdminRole>): Boolean {
    if (role == AdminRole.OWNER) return true
    return allowedRoles.contains(role)
}

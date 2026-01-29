package com.example.app.testutil

import com.example.db.AdminUsersRepository
import com.example.domain.AdminUser
import java.util.concurrent.ConcurrentHashMap

class InMemoryAdminUsersRepository : AdminUsersRepository {
    private val storage = ConcurrentHashMap<Pair<String, Long>, AdminUser>()

    fun put(user: AdminUser) {
        storage[user.merchantId to user.userId] = user
    }

    override suspend fun get(merchantId: String, userId: Long): AdminUser? {
        return storage[merchantId to userId]
    }

    override suspend fun upsert(user: AdminUser) {
        storage[user.merchantId to user.userId] = user
    }

    override suspend fun listByMerchant(merchantId: String): List<AdminUser> {
        return storage.values.filter { it.merchantId == merchantId }
    }
}

package com.example.app.services

import java.util.concurrent.ConcurrentHashMap

class PaymentDetailsStateStore {
    private val state = ConcurrentHashMap<Long, String>()

    fun start(adminId: Long, orderId: String) {
        state[adminId] = orderId
    }

    fun get(adminId: Long): String? = state[adminId]

    fun clear(adminId: Long) {
        state.remove(adminId)
    }
}

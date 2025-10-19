package com.example.bots

import com.pengrad.telegrambot.TelegramBot

class TelegramClients(
    adminToken: String,
    shopToken: String
) {
    val adminBot: TelegramBot = TelegramBot(adminToken)
    val shopBot: TelegramBot = TelegramBot(shopToken)
}

object AdminGuard {
    fun requireAdmin(userId: Long, allowed: Set<Long>) {
        require(allowed.contains(userId)) { "Forbidden: not an admin" }
    }
}

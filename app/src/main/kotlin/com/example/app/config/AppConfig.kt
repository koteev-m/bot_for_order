package com.example.app.config

data class AppConfig(
    val telegram: TelegramConfig,
    val db: DbConfig,
    val redis: RedisConfig,
    val payments: PaymentsConfig,
    val server: ServerConfig
)

data class TelegramConfig(
    val adminToken: String,
    val shopToken: String,
    val adminIds: Set<Long>,
    val channelId: Long
)

data class DbConfig(
    val url: String,
    val user: String,
    val password: String
)

data class RedisConfig(
    val url: String
)

data class PaymentsConfig(
    val providerToken: String,
    val invoiceCurrency: String
)

data class ServerConfig(
    val publicBaseUrl: String
)

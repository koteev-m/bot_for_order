package com.example.app.config

import java.net.URI

object ConfigLoader {

    fun fromEnv(): AppConfig {
        val adminToken = requireNonBlank("ADMIN_BOT_TOKEN")
        val shopToken = requireNonBlank("SHOP_BOT_TOKEN")
        val adminIds = parseAdminIds(requireNonBlank("ADMIN_IDS"))
        val channelId = parseLongEnv("CHANNEL_ID")

        val dbUrl = requireNonBlank("DATABASE_URL")
        val dbUser = requireNonBlank("DATABASE_USER")
        val dbPass = requireNonBlank("DATABASE_PASSWORD")

        val redisUrl = requireNonBlank("REDIS_URL")
        validateRedisUrl(redisUrl)

        val publicBaseUrl = requireNonBlank("PUBLIC_BASE_URL")
        validateHttps(publicBaseUrl)

        val providerToken = requireNonBlank("PROVIDER_TOKEN")
        val invoiceCurrency = requireNonBlank("INVOICE_CURRENCY").uppercase()

        return AppConfig(
            telegram = TelegramConfig(
                adminToken = adminToken,
                shopToken = shopToken,
                adminIds = adminIds,
                channelId = channelId
            ),
            db = DbConfig(
                url = dbUrl,
                user = dbUser,
                password = dbPass
            ),
            redis = RedisConfig(
                url = redisUrl
            ),
            payments = PaymentsConfig(
                providerToken = providerToken,
                invoiceCurrency = invoiceCurrency
            ),
            server = ServerConfig(
                publicBaseUrl = publicBaseUrl
            )
        )
    }

    private fun requireNonBlank(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("Missing required env: $name")

    private fun parseAdminIds(raw: String): Set<Long> =
        raw.split(",")
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .map {
                it.toLongOrNull()
                    ?: error("ADMIN_IDS contains non-numeric value: '$it'")
            }
            .toSet()
            .also { require(it.isNotEmpty()) { "ADMIN_IDS must not be empty" } }

    private fun parseLongEnv(name: String): Long =
        System.getenv(name)?.trim()
            ?.let { value -> value.toLongOrNull() ?: error("Env $name must be a number (got '$value')") }
            ?: error("Missing required env: $name")

    private fun validateHttps(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { error("PUBLIC_BASE_URL is not a valid URI: ${it.message}") }
        require(uri.scheme == "https") { "PUBLIC_BASE_URL must start with https://" }
    }

    private fun validateRedisUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { error("REDIS_URL invalid: ${it.message}") }
        require(uri.scheme?.startsWith("redis") == true) { "REDIS_URL must start with redis:// or rediss://" }
    }
}

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
        val offersExpireSweepSec = parsePositiveIntEnv("OFFERS_EXPIRE_SWEEP_SEC", defaultValue = 30)

        val providerToken = requireNonBlank("PROVIDER_TOKEN")
        val invoiceCurrency = requireNonBlank("INVOICE_CURRENCY").uppercase()
        val allowTips = parseBooleanEnv("PAYMENTS_ALLOW_TIPS", defaultValue = false)
        val tipSuggestions = parseTipSuggestions(System.getenv("PAYMENTS_TIP_SUGGESTED"))
        val shippingEnabled = parseBooleanEnv("SHIPPING_ENABLED", defaultValue = false)
        val shippingAllowlist = parseCountryAllowlist(System.getenv("SHIPPING_REGION_ALLOWLIST"))
        val shippingStdMinor = parseMinorAmount(System.getenv("SHIPPING_BASE_STD_MINOR"))
        val shippingExpMinor = parseMinorAmount(System.getenv("SHIPPING_BASE_EXP_MINOR"))
        val displayCurrencies = parseDisplayCurrencies(System.getenv("FX_DISPLAY_CURRENCIES"))
        val refreshIntervalSec = parseRefreshInterval(System.getenv("FX_REFRESH_INTERVAL_SEC"))

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
                invoiceCurrency = invoiceCurrency,
                allowTips = allowTips,
                suggestedTipAmountsMinor = tipSuggestions,
                shippingEnabled = shippingEnabled,
                shippingRegionAllowlist = shippingAllowlist,
                shippingBaseStdMinor = shippingStdMinor,
                shippingBaseExpMinor = shippingExpMinor
            ),
            server = ServerConfig(
                publicBaseUrl = publicBaseUrl,
                offersExpireSweepSec = offersExpireSweepSec
            ),
            fx = FxConfig(
                displayCurrencies = displayCurrencies,
                refreshIntervalSec = refreshIntervalSec
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

    private fun parseDisplayCurrencies(raw: String?): Set<String> {
        val fallback = setOf("RUB", "USD", "EUR", "USDT_TS")
        val values = raw?.split(",")
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.map(String::uppercase)
            ?.toSet()
            ?: fallback
        require(values.isNotEmpty()) { "FX_DISPLAY_CURRENCIES must contain at least one value" }
        return values
    }

    private fun parseRefreshInterval(raw: String?): Int {
        if (raw.isNullOrBlank()) {
            return 1800
        }
        val value = raw.trim().toIntOrNull()
            ?: error("FX_REFRESH_INTERVAL_SEC must be a number (got '$raw')")
        require(value > 0) { "FX_REFRESH_INTERVAL_SEC must be greater than 0" }
        return value
    }
}

private fun parseBooleanEnv(name: String, defaultValue: Boolean): Boolean {
    val raw = System.getenv(name) ?: return defaultValue
    return when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> error("Env $name must be true/false (got '$raw')")
    }
}

private fun parseTipSuggestions(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(",")
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .map { value ->
            val minor = value.toLongOrNull()
                ?: error("PAYMENTS_TIP_SUGGESTED contains non-numeric value '$value'")
            require(minor in 0..Int.MAX_VALUE) { "PAYMENTS_TIP_SUGGESTED must be >=0" }
            minor.toInt()
        }
}

private fun parseCountryAllowlist(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",")
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .map { value ->
            require(value.length == 2) { "SHIPPING_REGION_ALLOWLIST must use ISO-3166-1 alpha-2" }
            value.uppercase()
        }
        .toSet()
}

private fun parseMinorAmount(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0
    val value = raw.trim().toLongOrNull()
        ?: error("Shipping price must be numeric (got '$raw')")
    require(value >= 0) { "Shipping price must be >= 0" }
    return value
}

private fun parsePositiveIntEnv(name: String, defaultValue: Int): Int {
    val raw = System.getenv(name)?.takeIf { it.isNotBlank() } ?: return defaultValue
    val value = raw.trim().toIntOrNull()
        ?: error("Env $name must be a number (got '$raw')")
    require(value > 0) { "Env $name must be > 0" }
    return value
}

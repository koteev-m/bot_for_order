package com.example.app.config

import java.net.URI

internal fun validateHttps(url: String) {
    val uri = runCatching { URI(url) }.getOrElse { error("PUBLIC_BASE_URL is not a valid URI: ${it.message}") }
    require(uri.scheme == "https") { "PUBLIC_BASE_URL must start with https://" }
}

internal fun validateRedisUrl(url: String) {
    val uri = runCatching { URI(url) }.getOrElse { error("REDIS_URL invalid: ${it.message}") }
    require(uri.scheme?.startsWith("redis") == true) { "REDIS_URL must start with redis:// or rediss://" }
}

internal fun validateStorageEndpoint(url: String) {
    val uri = runCatching { URI(url) }.getOrElse { error("STORAGE_ENDPOINT invalid: ${it.message}") }
    require(uri.scheme == "http" || uri.scheme == "https") {
        "STORAGE_ENDPOINT must start with http:// or https://"
    }
}

internal fun parseDisplayCurrencies(raw: String?): Set<String> {
    val fallback = setOf("RUB", "USD", "EUR", "USDT_TS")
    val values = raw?.split(",")
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        ?.map(String::uppercase)
        ?.toSet()
        ?: fallback
    require(values.isNotEmpty()) { "FX_DISPLAY_CURRENCIES must contain at least one value" }
    return values
}

internal fun parseRefreshInterval(raw: String?): Int {
    if (raw.isNullOrBlank()) {
        return 1800
    }
    val value = raw.trim().toIntOrNull()
        ?: error("FX_REFRESH_INTERVAL_SEC must be a number (got '$raw')")
    require(value > 0) { "FX_REFRESH_INTERVAL_SEC must be greater than 0" }
    return value
}

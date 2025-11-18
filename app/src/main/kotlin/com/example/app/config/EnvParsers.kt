package com.example.app.config

internal fun requireNonBlank(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required env: $name")

internal fun parseAdminIds(raw: String): Set<Long> =
    raw.split(",")
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .map {
            it.toLongOrNull()
                ?: error("ADMIN_IDS contains non-numeric value: '$it'")
        }
        .toSet()
        .also { require(it.isNotEmpty()) { "ADMIN_IDS must not be empty" } }

internal fun parseLongEnv(name: String): Long =
    System.getenv(name)?.trim()
        ?.let { value -> value.toLongOrNull() ?: error("Env $name must be a number (got '$value')") }
        ?: error("Missing required env: $name")

internal fun parseBooleanEnv(name: String, defaultValue: Boolean): Boolean {
    val raw = System.getenv(name) ?: return defaultValue
    return when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> error("Env $name must be true/false (got '$raw')")
    }
}

internal fun parseTipSuggestions(raw: String?): List<Int> {
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

internal fun parseCountryAllowlist(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",")
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .map { value ->
            require(value.length == 2) { "SHIPPING_REGION_ALLOWLIST must use ISO-3166-1 alpha-2" }
            value.uppercase()
        }
        .toSet()
}

internal fun parseMinorAmount(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0
    val value = raw.trim().toLongOrNull()
        ?: error("Shipping price must be numeric (got '$raw')")
    require(value >= 0) { "Shipping price must be >= 0" }
    return value
}

internal fun parsePositiveIntEnv(name: String, defaultValue: Int): Int {
    val raw = System.getenv(name)?.takeIf { it.isNotBlank() } ?: return defaultValue
    val value = raw.trim().toIntOrNull()
        ?: error("Env $name must be a number (got '$raw')")
    require(value > 0) { "Env $name must be > 0" }
    return value
}

package com.example.app.config

internal fun parsePositiveLongEnv(name: String, defaultValue: Long): Long {
    val raw = System.getenv(name)?.takeIf { it.isNotBlank() } ?: return defaultValue
    val value = raw.trim().toLongOrNull()
        ?: error("Env $name must be a number (got '$raw')")
    require(value > 0) { "Env $name must be > 0" }
    return value
}

internal fun parseLogLevelEnv(name: String, defaultValue: String): String {
    val value = System.getenv(name)?.takeIf { it.isNotBlank() }?.trim()?.uppercase() ?: defaultValue
    require(value in setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")) {
        "Env $name must be one of TRACE, DEBUG, INFO, WARN, ERROR"
    }
    return value
}

internal fun parseBase64KeyEnv(name: String, expectedSize: Int): ByteArray {
    val raw = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required env: $name")
    val decoded = runCatching { java.util.Base64.getDecoder().decode(raw.trim()) }
        .getOrElse { error("Env $name must be base64: ${it.message}") }
    require(decoded.size == expectedSize) { "Env $name must be $expectedSize bytes" }
    return decoded
}

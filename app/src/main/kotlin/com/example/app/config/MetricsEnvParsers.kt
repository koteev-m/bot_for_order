package com.example.app.config

internal fun parseBasicAuthEnv(name: String): BasicAuth? {
    val raw = System.getenv(name)?.takeIf { it.isNotBlank() } ?: return null
    val parts = raw.split(":", limit = 2)
    require(parts.size == 2) { "Env $name must be in 'user:password' format" }
    val user = parts[0].trim()
    val password = parts[1]
    require(user.isNotEmpty()) { "Env $name must have non-empty user" }
    return BasicAuth(user = user, password = password)
}

internal fun parseIpAllowlistEnv(name: String): Set<String> {
    val raw = System.getenv(name)?.takeIf { it.isNotBlank() } ?: return emptySet()
    return raw.split(",")
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .toSet()
}

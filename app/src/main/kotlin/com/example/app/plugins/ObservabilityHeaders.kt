package com.example.app.plugins

import com.example.app.config.HstsConfig
import com.example.app.routes.PRESET_VARY_TOKENS
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.onCall
import io.ktor.server.application.onCallRespond
import io.ktor.util.AttributeKey

val OBS_VARY_TOKENS = AttributeKey<MutableSet<String>>("obs-vary-tokens")
val OBS_COMMON_ENABLED = AttributeKey<Boolean>("obs-common-enabled")

class ObservabilityHeadersConfig {
    var hsts: HstsConfig = HstsConfig()
    var canonicalVary: Map<String, String> = emptyMap()
    var extraCommonHeaders: Map<String, String> = linkedMapOf(
        HttpHeaders.CacheControl to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'",
        "Pragma" to "no-cache",
        "Cross-Origin-Resource-Policy" to "same-origin",
        "X-Permitted-Cross-Domain-Policies" to "none",
        "Expires" to "0",
    )
}

val ObservabilityHeaders = createApplicationPlugin(
    name = "ObservabilityHeaders",
    createConfiguration = ::ObservabilityHeadersConfig,
) {
    val hstsConfig = pluginConfig.hsts
    val canonicalVary = pluginConfig.canonicalVary
    val extraCommonHeaders = pluginConfig.extraCommonHeaders

    onCall { call ->
        if (call.attributes.getOrNull(OBS_VARY_TOKENS) == null) {
            call.attributes.put(OBS_VARY_TOKENS, mutableSetOf())
        }
        if (call.attributes.getOrNull(OBS_COMMON_ENABLED) == null) {
            call.attributes.put(OBS_COMMON_ENABLED, true)
        }
    }

    onCallRespond { call, _ ->
        if (call.attributes.getOrNull(OBS_COMMON_ENABLED) == true) {
            writeCommonHeaders(call, extraCommonHeaders)
        }
        writeVary(call, canonicalVary)
        maybeAppendHsts(call, hstsConfig)
    }
}

private fun writeCommonHeaders(call: ApplicationCall, headers: Map<String, String>) {
    headers.forEach { (name, value) ->
        if (call.response.headers[name] == value) return@forEach
        call.response.headers.append(name, value)
    }
}

private fun writeVary(call: ApplicationCall, canonicalVary: Map<String, String>) {
    val name = HttpHeaders.Vary
    val seen = LinkedHashMap<String, String>()

    fun addToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val lower = trimmed.lowercase()
        val canonical = canonicalVary[lower] ?: trimmed
        seen.putIfAbsent(lower, canonical)
    }

    val existing = call.response.headers.allValues()
    if (existing is Map<*, *>) {
        existing.entries.forEach { entry ->
            val key = entry.key
            val value = entry.value
            if (key is String && key.equals(name, ignoreCase = true) && value is List<*>) {
                value.filterIsInstance<String>().forEach { line ->
                    line.split(',').forEach(::addToken)
                }
            }
        }
    }

    call.response.headers[name]?.let { line ->
        line.split(',').forEach(::addToken)
    }

    call.attributes.getOrNull(PRESET_VARY_TOKENS)?.forEach(::addToken)
    call.attributes.getOrNull(OBS_VARY_TOKENS)?.forEach(::addToken)

    if (seen.isEmpty()) return

    val joined = seen.values.joinToString(", ")
    if (call.response.headers[name] == joined) return
    call.response.headers.append(name, joined)
}

private fun maybeAppendHsts(call: ApplicationCall, hsts: HstsConfig) {
    if (!hsts.enabled) return
    val forwardedProto = call.request.headers["X-Forwarded-Proto"]?.lowercase()
    val forwardedHdr = call.request.headers[HttpHeaders.Forwarded]
    val viaForwarded = forwardedProtoIsHttps(forwardedHdr)
    val isHttps = viaForwarded || forwardedProto == "https" || call.request.local.scheme == "https"
    if (!isHttps) return

    val directives = buildList {
        add("max-age=${hsts.maxAgeSeconds}")
        if (hsts.includeSubdomains) add("includeSubDomains")
        if (hsts.preload) add("preload")
    }.joinToString("; ")

    if (call.response.headers["Strict-Transport-Security"] == directives) return
    call.response.headers.append("Strict-Transport-Security", directives)
}

private fun forwardedProtoIsHttps(forwardedHeader: String?): Boolean {
    if (forwardedHeader.isNullOrBlank()) return false
    // Support multiple elements: Forwarded: for=..., proto=https;..., for=...
    return forwardedHeader.split(',')
        .asSequence()
        .map { it.trim() }
        .flatMap { elem -> elem.split(';').asSequence().map { it.trim() } }
        .any { part -> part.startsWith("proto=", ignoreCase = true) && part.substringAfter('=').trim().trim('"').equals("https", ignoreCase = true) }
}

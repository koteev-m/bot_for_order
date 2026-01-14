package com.example.app.plugins

import com.example.app.config.HstsConfig
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.ResponseHeaders
import io.ktor.util.AttributeKey
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.Logger

val OBS_VARY_TOKENS = AttributeKey<MutableSet<String>>("obs-vary-tokens")
val OBS_COMMON_ENABLED = AttributeKey<Boolean>("obs-common-enabled")
val OBS_ENABLED = AttributeKey<Boolean>("obs-enabled")
val OBS_EXTRA_HEADERS = AttributeKey<MutableMap<String, String>>("obs-extra-headers")
val PRESET_VARY_TOKENS = AttributeKey<Set<String>>("preset-vary-tokens")

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
    var aggressiveReplaceStrictHeaders: Boolean = false
    val strictHeaders: Set<String> = setOf(
        HttpHeaders.CacheControl,
        "Pragma",
        "Expires",
        "X-Frame-Options",
        "X-Content-Type-Options",
        "Referrer-Policy",
        "Content-Security-Policy",
        "Cross-Origin-Resource-Policy",
        "X-Permitted-Cross-Domain-Policies",
        "Strict-Transport-Security"
    )
}

val ObservabilityHeaders = createApplicationPlugin(
    name = "ObservabilityHeaders",
    createConfiguration = ::ObservabilityHeadersConfig,
) {
    val hstsConfig = pluginConfig.hsts
    val canonicalVary = normalizeCanonicalVary(pluginConfig.canonicalVary, application.environment.log)
    val extraCommonHeaders = pluginConfig.extraCommonHeaders
    val aggressiveReplaceStrictHeaders = pluginConfig.aggressiveReplaceStrictHeaders
    val strictHeaders = pluginConfig.strictHeaders.map { it.lowercase(Locale.ROOT) }.toSet()
    val logger = application.environment.log
    val removalSupport = HeaderRemovalSupport(logger)
    val headerWriter = HeaderWriter(
        aggressiveReplaceStrictHeaders = aggressiveReplaceStrictHeaders,
        strictHeaders = strictHeaders,
        removalSupport = removalSupport,
        logger = logger,
    )

    onCallRespond { call, _ ->
        val commonEnabled = call.attributes.getOrNull(OBS_COMMON_ENABLED) == true ||
            call.attributes.getOrNull(OBS_ENABLED) == true
        if (commonEnabled) {
            val perRoute = call.attributes.getOrNull(OBS_EXTRA_HEADERS)
            val headersToWrite = if (perRoute.isNullOrEmpty()) {
                extraCommonHeaders
            } else {
                LinkedHashMap(extraCommonHeaders).also { merged ->
                    perRoute.forEach { (name, value) -> merged[name] = value }
                }
            }
            writeCommonHeaders(call, headersToWrite, headerWriter)
        }
        writeVary(call, canonicalVary, removalSupport)
        if (commonEnabled) {
            maybeAppendHsts(call, hstsConfig, headerWriter)
        }
    }
}

private fun normalizeCanonicalVary(
    canonicalVary: Map<String, String>,
    logger: Logger,
): Map<String, String> {
    if (canonicalVary.isEmpty()) return canonicalVary
    val normalized = LinkedHashMap<String, String>(canonicalVary.size)
    canonicalVary.forEach { (key, value) ->
        val normalizedKey = key.trim().lowercase(Locale.ROOT)
        val existing = normalized[normalizedKey]
        if (existing != null) {
            logger.debug(
                "Canonical Vary key collision after normalizing for '{}'(from '{}'); keeping first value '{}', ignoring '{}' from '{}'.",
                normalizedKey,
                key,
                existing,
                value,
                key,
            )
        } else {
            normalized[normalizedKey] = value
        }
    }
    return normalized
}

private fun writeCommonHeaders(
    call: ApplicationCall,
    headers: Map<String, String>,
    headerWriter: HeaderWriter,
) {
    headers.forEach { (name, value) ->
        headerWriter.append(
            call.response.headers,
            name,
            value,
        )
    }
}

private fun writeVary(
    call: ApplicationCall,
    canonicalVary: Map<String, String>,
    removalSupport: HeaderRemovalSupport,
) {
    val commonEnabled = call.attributes.getOrNull(OBS_COMMON_ENABLED) == true ||
        call.attributes.getOrNull(OBS_ENABLED) == true
    val presetTokens = call.attributes.getOrNull(PRESET_VARY_TOKENS)
    val obsTokens = call.attributes.getOrNull(OBS_VARY_TOKENS)
    val hasExplicitTokens = !presetTokens.isNullOrEmpty() || !obsTokens.isNullOrEmpty()
    if (!commonEnabled && !hasExplicitTokens) return

    val name = HttpHeaders.Vary
    val seen = LinkedHashMap<String, String>()

    fun addToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val lower = trimmed.lowercase(Locale.ROOT)
        val canonicalCandidate = canonicalVary[lower]?.trim().orEmpty()
        val canonical = when {
            canonicalCandidate.isEmpty() -> trimmed
            isValidVaryToken(canonicalCandidate) -> canonicalCandidate
            else -> trimmed
        }
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

    presetTokens?.forEach(::addToken)
    obsTokens?.forEach(::addToken)

    if (seen.isEmpty()) return

    val joined = seen.values.joinToString(", ")
    val existingValues = call.response.headers.values(name)
    if (existingValues.size == 1 && existingValues.first() == joined) return
    if (existingValues.isNotEmpty()) {
        removalSupport.remove(call.response.headers, name)
        if (call.response.headers.values(name).isNotEmpty()) return
    }
    call.response.headers.append(name, joined)
}

private fun isValidVaryToken(token: String): Boolean =
    token.none { it == ',' || it.isWhitespace() || it.isISOControl() }

private fun maybeAppendHsts(
    call: ApplicationCall,
    hsts: HstsConfig,
    headerWriter: HeaderWriter,
) {
    if (!hsts.enabled) return
    val forwardedProto = call.request.headers["X-Forwarded-Proto"]?.lowercase(Locale.ROOT)
    val forwardedHdr = call.request.headers[HttpHeaders.Forwarded]
    val viaForwarded = forwardedProtoIsHttps(forwardedHdr)
    val isHttps = viaForwarded || forwardedProto == "https" || call.request.local.scheme == "https"
    if (!isHttps) return

    val directives = buildList {
        add("max-age=${hsts.maxAgeSeconds}")
        if (hsts.includeSubdomains) add("includeSubDomains")
        if (hsts.preload) add("preload")
    }.joinToString("; ")

    val name = "Strict-Transport-Security"
    headerWriter.append(
        call.response.headers,
        name,
        directives,
    )
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

internal class HeaderWriter(
    private val aggressiveReplaceStrictHeaders: Boolean,
    private val strictHeaders: Set<String>,
    private val removalSupport: HeaderRemovalSupport,
    private val logger: Logger,
) {
    fun append(headers: ResponseHeaders, name: String, value: String) {
        val isStrict = name.lowercase(Locale.ROOT) in strictHeaders
        if (isStrict) {
            val existingValues = headers.values(name)
            if (!aggressiveReplaceStrictHeaders) {
                when {
                    existingValues.isEmpty() -> headers.append(name, value)
                    existingValues.size == 1 && existingValues.first() == value -> return
                    else -> {
                        if (logger.isDebugEnabled) {
                            logger.debug(
                                "Strict header conflict for {} in non-aggressive mode; keeping existing values: {}",
                                name,
                                summarizeValues(existingValues),
                            )
                        }
                        return
                    }
                }
                return
            }
            when {
                existingValues.isEmpty() -> {
                    headers.append(name, value)
                    return
                }
                existingValues.size == 1 && existingValues.first() == value -> return
                else -> {
                    removalSupport.remove(headers, name)
                    if (headers.values(name).isNotEmpty()) {
                        logger.debug(
                            "Unable to replace strict header {} on {}. Leaving existing values.",
                            name,
                            headers.javaClass.name,
                        )
                        return
                    }
                    headers.append(name, value)
                    return
                }
            }
        }

        if (headers.values(name).any { it == value }) return
        headers.append(name, value)
    }

    private fun summarizeValues(
        values: List<String>,
        maxValues: Int = 3,
        maxValueLength: Int = 200,
    ): String {
        if (values.isEmpty()) return "[]"
        val truncated = values.take(maxValues).map { value ->
            if (value.length > maxValueLength) {
                value.take(maxValueLength) + "…"
            } else {
                value
            }
        }
        val suffix = if (values.size > maxValues) " (+${values.size - maxValues} more)" else ""
        return truncated.joinToString(prefix = "[", postfix = "]$suffix")
    }
}

internal class HeaderRemovalSupport(private val logger: Logger) {
    private val removeMethods = ConcurrentHashMap<Class<*>, RemoveMethodLookup>()
    private val loggedUnavailable = AtomicBoolean(false)
    private val loggedFailure = AtomicBoolean(false)

    fun remove(headers: ResponseHeaders, name: String): Boolean {
        val lookup = removeMethods.computeIfAbsent(headers.javaClass, ::findRemoveMethod)
        if (lookup is RemoveMethodLookup.Found) {
            return try {
                lookup.method.invoke(headers, name)
                if (headers.values(name).isEmpty()) {
                    true
                } else {
                    tryClearHeaderValues(headers, name)
                }
            } catch (ex: Exception) {
                if (loggedFailure.compareAndSet(false, true)) {
                    logger.debug("Failed to remove header via reflective call; falling back to append.", ex)
                }
                tryClearHeaderValues(headers, name)
            }
        }
        return tryClearHeaderValues(headers, name)
    }

    private fun findRemoveMethod(headersClass: Class<*>): RemoveMethodLookup {
        val method = headersClass.methods.firstOrNull(::isRemoveMethod)
            ?: headersClass.declaredMethods.firstOrNull(::isRemoveMethod)?.also { it.isAccessible = true }
        if (method == null && loggedUnavailable.compareAndSet(false, true)) {
            logger.debug(
                "Header removal method not available for {}; falling back to append.",
                headersClass.name,
            )
        }
        return if (method == null) RemoveMethodLookup.Missing else RemoveMethodLookup.Found(method)
    }

    private fun isRemoveMethod(method: Method): Boolean =
        method.name == "remove" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == String::class.java

    private fun tryClearHeaderValues(headers: ResponseHeaders, name: String): Boolean {
        val values = headers.values(name)
        if (values.isEmpty()) return true
        return try {
            if (values is MutableList<*>) {
                values.clear()
                headers.values(name).isEmpty()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private sealed interface RemoveMethodLookup {
        data class Found(val method: Method) : RemoveMethodLookup
        object Missing : RemoveMethodLookup
    }
}

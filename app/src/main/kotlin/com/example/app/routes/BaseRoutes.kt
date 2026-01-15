package com.example.app.routes

import com.example.app.api.respondApiError
import com.example.app.config.AppConfig
import com.example.app.config.BasicAuthCompatConfig
import com.example.app.observability.BuildInfoProvider
import com.example.app.plugins.OBS_COMMON_ENABLED
import com.example.app.plugins.OBS_VARY_TOKENS
import com.example.app.plugins.ObservabilityHeaders
import com.example.app.plugins.ObservabilityHeadersConfig
import com.example.app.util.CidrMatcher
import com.example.app.util.ClientIpResolver
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.ktor.ext.inject
import org.redisson.api.RedissonClient
import org.redisson.api.redisnode.RedisNodes

private val METRICS_VARY_TOKENS = listOf(
    HttpHeaders.Authorization,
    HttpHeaders.XForwardedFor,
    HttpHeaders.Forwarded,
    "X-Real-IP",
    "CF-Connecting-IP",
    "True-Client-IP"
)

private val VARY_CANONICAL = METRICS_VARY_TOKENS.associateBy { it.lowercase(Locale.ROOT) }

private const val MAX_BASIC_AUTH_ENCODED = 8192
private const val MAX_BASIC_AUTH_DECODED = 4096
private const val MAX_BASIC_USER = 256
private const val MAX_BASIC_PASS = 2048

private fun hasControlChars(s: String): Boolean =
    s.any { ch -> ch <= '\u001F' || ch == '\u007F' }

private fun constantTimeEquals(a: String, b: String): Boolean {
    val ab = a.toByteArray(StandardCharsets.UTF_8)
    val bb = b.toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.isEqual(ab, bb)
}

private fun basicAuthValid(
    header: String?,
    expectedUser: String,
    expectedPass: String,
    compat: BasicAuthCompatConfig,
): Boolean {
    val blob = header
        ?.takeIf { it.startsWith("Basic", ignoreCase = true) }
        ?.removePrefix("Basic")
        ?.trim()
        ?: return false

    if (blob.length > MAX_BASIC_AUTH_ENCODED) return false

    val decoded = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return false

    // size guard
    if (decoded.size > MAX_BASIC_AUTH_DECODED) return false

    var creds = String(decoded, StandardCharsets.UTF_8)
    val utf8Ok = !hasControlChars(creds) && !creds.contains('\uFFFD')
    if (!utf8Ok) {
        if (!compat.latin1Fallback) return false
        val latin = String(decoded, java.nio.charset.StandardCharsets.ISO_8859_1)
        if (hasControlChars(latin)) return false
        creds = latin
    }

    val parts = creds.split(":", limit = 2)
    if (parts.size != 2) return false

    val user = parts[0]
    val pass = parts[1]

    // length guards
    if (user.length > MAX_BASIC_USER || pass.length > MAX_BASIC_PASS) return false

    val userOk = constantTimeEquals(user, expectedUser)
    val passOk = constantTimeEquals(pass, expectedPass)
    return userOk && passOk
}

private fun normalizedRealm(raw: String): String {
    val cleaned = raw.filter { it >= ' ' && it != '\u007F' }.trim().take(256)
    return cleaned.replace("\\", "\\\\").replace("\"", "\\\"")
}

fun Application.installBaseRoutes(
    cfg: AppConfig,
    registry: MeterRegistry?,
    observabilityConfig: ObservabilityHeadersConfig.() -> Unit = {},
) {
    val database by inject<Database>()
    val redisson by inject<RedissonClient>()

    install(ObservabilityHeaders) {
        hsts = cfg.security.hsts
        canonicalVary = VARY_CANONICAL
        observabilityConfig()
    }

    routing {
        get("/health") {
            call.attributes.put(OBS_COMMON_ENABLED, true)
            val checks = listOf(
                databaseHealthCheck(database, cfg.health.dbTimeoutMs),
                redisHealthCheck(redisson, cfg.health.redisTimeoutMs)
            )
            val overallUp = checks.all { it.status == "UP" }
            val status = if (overallUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, HealthResponse(status = if (overallUp) "UP" else "DOWN", checks = checks))
        }
        get("/build") {
            call.attributes.put(OBS_COMMON_ENABLED, true)
            call.respond(BuildInfoProvider.current())
        }
        if (cfg.metrics.prometheusEnabled && registry is PrometheusMeterRegistry) {
            get("/metrics") {
                val requestLogger = environment.log
                call.attributes.put(OBS_COMMON_ENABLED, true)
                // Кэш и вариативность должны присутствовать на любом исходе маршрута
                val varyTokens = call.attributes.getOrNull(OBS_VARY_TOKENS)
                    ?: mutableSetOf<String>().also { call.attributes.put(OBS_VARY_TOKENS, it) }
                varyTokens.addAll(METRICS_VARY_TOKENS)
                val clientIp = ClientIpResolver.resolve(call, cfg.metrics.trustedProxyAllowlist)

                if (cfg.metrics.ipAllowlist.isNotEmpty() && !CidrMatcher.isAllowed(clientIp, cfg.metrics.ipAllowlist)) {
                    call.respondApiError(requestLogger, HttpStatusCode.Forbidden, "forbidden", warn = true)
                    return@get
                }

                val expectedAuth = cfg.metrics.basicAuth
                if (expectedAuth != null) {
                    val isValid = basicAuthValid(
                        call.request.header(HttpHeaders.Authorization),
                        expectedAuth.user,
                        expectedAuth.password,
                        cfg.security.basicAuthCompat,
                    )
                    if (!isValid) {
                        val challenge = "Basic realm=\"${normalizedRealm(cfg.metrics.basicRealm)}\", charset=\"UTF-8\""
                        if (call.response.headers[HttpHeaders.WWWAuthenticate] != challenge) {
                            call.response.headers.append(HttpHeaders.WWWAuthenticate, challenge)
                        }
                        call.respondApiError(requestLogger, HttpStatusCode.Unauthorized, "unauthorized", warn = true)
                        return@get
                    }
                }

                call.respondText(
                    registry.scrape(),
                    ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
                )
            }
        }
    }
}

private suspend fun databaseHealthCheck(database: Database, timeoutMs: Long): HealthCheckResult {
    var ok = false
    val duration = measureTimeMillis {
        ok = withTimeoutOrNull(timeoutMs) {
            runCatching {
                newSuspendedTransaction(Dispatchers.IO, database) {
                    exec("SELECT 1") { it.next() }
                }
            }.isSuccess
        } ?: false
    }
    return HealthCheckResult("db", if (ok) "UP" else "DOWN", duration)
}

private suspend fun redisHealthCheck(redisson: RedissonClient, timeoutMs: Long): HealthCheckResult {
    var ok = false
    val duration = measureTimeMillis {
        ok = withTimeoutOrNull(timeoutMs.milliseconds) {
            val config = redisson.config
            val detectedTopology = when {
                config.isSingleConfig -> RedisNodes.SINGLE
                config.isClusterConfig -> RedisNodes.CLUSTER
                config.isSentinelConfig -> RedisNodes.SENTINEL_MASTER_SLAVE
                else -> null
            }
            val pingResult = detectedTopology
                ?.let { topology -> runCatching { redisson.getRedisNodes(topology).pingAll() }.getOrNull() }
                ?: redisHealthFallbackTopologies.firstNotNullOfOrNull { topology ->
                    runCatching { redisson.getRedisNodes(topology).pingAll() }.getOrNull()
                }
            pingResult ?: false
        } ?: false
    }
    return HealthCheckResult("redis", if (ok) "UP" else "DOWN", duration)
}

private val redisHealthFallbackTopologies = listOf(
    RedisNodes.SINGLE,
    RedisNodes.MASTER_SLAVE,
    RedisNodes.CLUSTER,
    RedisNodes.SENTINEL_MASTER_SLAVE
)

@Serializable
private data class HealthResponse(
    val status: String,
    val checks: List<HealthCheckResult>,
)

@Serializable
private data class HealthCheckResult(
    val name: String,
    val status: String,
    val durationMs: Long,
)

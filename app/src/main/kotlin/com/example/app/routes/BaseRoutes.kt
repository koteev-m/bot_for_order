package com.example.app.routes

import com.example.app.api.respondApiError
import com.example.app.config.AppConfig
import com.example.app.observability.BuildInfoProvider
import com.example.app.util.ClientIpResolver
import com.example.app.util.CidrMatcher
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.request.header
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.ktor.ext.inject
import org.redisson.api.RedissonClient
import org.redisson.api.redisnode.RedisNodes

@Suppress("LongMethod")
fun Application.installBaseRoutes(cfg: AppConfig, registry: MeterRegistry?) {
    val database by inject<Database>()
    val redisson by inject<RedissonClient>()

    routing {
        get("/health") {
            val checks = listOf(
                databaseHealthCheck(database, cfg.health.dbTimeoutMs),
                redisHealthCheck(redisson, cfg.health.redisTimeoutMs)
            )
            val overallUp = checks.all { it.status == "UP" }
            val status = if (overallUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, HealthResponse(status = if (overallUp) "UP" else "DOWN", checks = checks))
        }
        get("/build") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(BuildInfoProvider.current())
        }
        if (cfg.metrics.prometheusEnabled && registry is PrometheusMeterRegistry) {
            get("/metrics") {
                val requestLogger = environment.log
                // Кэш и вариативность должны присутствовать на любом исходе маршрута
                val varyValue = listOf(
                    HttpHeaders.Authorization,
                    HttpHeaders.XForwardedFor,
                    HttpHeaders.Forwarded,
                    "X-Real-IP",
                    "CF-Connecting-IP",
                    "True-Client-IP"
                ).joinToString(", ")
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append(HttpHeaders.Vary, varyValue)
                val clientIp = ClientIpResolver.resolve(call, cfg.metrics.trustedProxyAllowlist)

                if (cfg.metrics.ipAllowlist.isNotEmpty() && !CidrMatcher.isAllowed(clientIp, cfg.metrics.ipAllowlist)) {
                    call.respondApiError(requestLogger, HttpStatusCode.Forbidden, "forbidden", warn = true)
                    return@get
                }

                val expectedAuth = cfg.metrics.basicAuth
                if (expectedAuth != null) {
                    val isValid = call.request.header(HttpHeaders.Authorization)
                        ?.takeIf { it.startsWith("Basic", ignoreCase = true) }
                        ?.removePrefix("Basic")
                        ?.trim()
                        ?.let { blob ->
                            runCatching {
                                val decoded = Base64.getDecoder().decode(blob).toString(StandardCharsets.UTF_8)
                                val parts = decoded.split(":", limit = 2)
                                parts.size == 2 && parts[0] == expectedAuth.user && parts[1] == expectedAuth.password
                            }.getOrNull()
                        } ?: false
                    if (!isValid) {
                        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"metrics\"")
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
    val start = System.currentTimeMillis()
    val ok = withTimeoutOrNull(timeoutMs.milliseconds) {
        val config = redisson.config
        val detectedTopology = when {
            config.isSingleConfig -> RedisNodes.SINGLE
            config.isClusterConfig -> RedisNodes.CLUSTER
            config.isSentinelConfig -> RedisNodes.SENTINEL_MASTER_SLAVE
            else -> null
        }
        val pingResult = detectedTopology
            ?.let { redisson.getRedisNodes(it).pingAll() }
            ?: listOf(
                RedisNodes.SINGLE,
                RedisNodes.MASTER_SLAVE,
                RedisNodes.CLUSTER,
                RedisNodes.SENTINEL_MASTER_SLAVE
            ).firstNotNullOfOrNull { topology ->
                runCatching { redisson.getRedisNodes(topology).pingAll() }.getOrNull()
            }
        pingResult ?: false
    } ?: false
    return HealthCheckResult("redis", if (ok) "UP" else "DOWN", System.currentTimeMillis() - start)
}

private data class HealthResponse(
    val status: String,
    val checks: List<HealthCheckResult>,
)

private data class HealthCheckResult(
    val name: String,
    val status: String,
    val durationMs: Long,
)

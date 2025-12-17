package com.example.app

import com.example.app.config.BasicAuth
import com.example.app.config.MetricsConfig
import com.example.app.config.HealthConfig
import com.example.app.observability.registerBuildInfoMeter
import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

private val expectedVary = listOf(
    HttpHeaders.Authorization,
    HttpHeaders.XForwardedFor,
    HttpHeaders.Forwarded,
    "X-Real-IP",
    "CF-Connecting-IP",
    "True-Client-IP"
).joinToString(", ")

class MetricsSecurityTest : StringSpec({
    "rejects missing or bad basic auth" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) {
                    modules(module { single { database }; single { redisson } })
                }
                routing { installBaseRoutes(cfg, registry) }
            }

            val missing = client.get("/metrics")
            missing.status shouldBe HttpStatusCode.Unauthorized
            missing.headers[HttpHeaders.WWWAuthenticate] shouldBe "Basic realm=\"metrics\", charset=\"UTF-8\""
            missing.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            missing.headers[HttpHeaders.Vary] shouldBe expectedVary

            val bad = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:bad")}")
            }
            bad.status shouldBe HttpStatusCode.Unauthorized
            bad.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            bad.headers[HttpHeaders.Vary] shouldBe expectedVary
        }
    }

    "serves metrics with correct content type when authorized" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("127.0.0.1"),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registerBuildInfoMeter(registry)

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) {
                    modules(module { single { database }; single { redisson } })
                }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "127.0.0.1")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            response.headers[HttpHeaders.Vary] shouldBe expectedVary
            val contentType = response.headers[HttpHeaders.ContentType] ?: error("missing content type")
            ContentType.parse(contentType).let {
                it.contentType shouldBe "text"
                it.contentSubtype shouldBe "plain"
                it.parameter("version") shouldBe "0.0.4"
            }
            val body = response.bodyAsText()
            body.contains("# TYPE") shouldBe true
            body.lineSequence().any { line ->
                line.startsWith("build_info{") && line.trim().endsWith("} 1")
            } shouldBe true
        }
    }

    "blocks disallowed ip even with auth" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("10.0.0.0/8"),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) {
                    modules(module { single { database }; single { redisson } })
                }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "203.0.113.10")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            response.headers[HttpHeaders.Vary] shouldBe expectedVary
        }
    }
})

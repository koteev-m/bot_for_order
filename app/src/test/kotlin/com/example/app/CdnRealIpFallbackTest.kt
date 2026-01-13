package com.example.app

import com.example.app.config.BasicAuth
import com.example.app.config.HealthConfig
import com.example.app.config.MetricsConfig
import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
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

class CdnRealIpFallbackTest : StringSpec({
    "uses CF-Connecting-IP when remote is trusted and XFF/Forwarded absent" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("203.0.113.0/24"),
                trustedProxyAllowlist = setOf("127.0.0.1")
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }
            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("CF-Connecting-IP", "203.0.113.5")
            }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "uses X-Real-IP when remote is trusted and XFF/Forwarded absent" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("198.51.100.0/24"),
                trustedProxyAllowlist = setOf("127.0.0.1")
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }
            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("X-Real-IP", "198.51.100.23")
            }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "uses True-Client-IP when remote is trusted and XFF/Forwarded absent" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("198.51.100.0/24"),
                trustedProxyAllowlist = setOf("127.0.0.1")
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }
            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("True-Client-IP", "198.51.100.99")
            }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "ignores CF-Connecting-IP when remote is untrusted" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("203.0.113.0/24"),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }
            // remote не в trustedProxyAllowlist -> фолбэк по CF-Connecting-IP игнорируется, проверяется реальный remote
            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("CF-Connecting-IP", "203.0.113.5")
            }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }

    "prefers True-Client-IP over CF and X-Real-IP when all present" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("198.51.100.0/24"), // матчится по True-Client-IP
                trustedProxyAllowlist = setOf("127.0.0.1")
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }
            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("True-Client-IP", "198.51.100.99") // приоритет №1
                header("CF-Connecting-IP", "203.0.113.5")  // приоритет №2
                header("X-Real-IP", "192.0.2.1")           // приоритет №3
            }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "rejects when True-Client-IP is disallowed even if CF-Connecting-IP is allowed" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("203.0.113.0/24"), // матчится по CF-Connecting-IP, но TCI имеет приоритет
                trustedProxyAllowlist = setOf("127.0.0.1")
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }
            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("True-Client-IP", "198.51.100.99") // не в allowlist, приоритет №1
                header("CF-Connecting-IP", "203.0.113.5")  // в allowlist, но приоритет ниже
            }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }
})

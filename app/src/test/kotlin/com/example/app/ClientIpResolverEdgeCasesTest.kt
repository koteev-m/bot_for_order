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

class ClientIpResolverEdgeCasesTest : StringSpec({
    "accepts IPv6 from XFF when proxy is trusted (strip ::1 hop)" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("2001:db8::/32"),
                // В тестовом движке remoteHost=127.0.0.1 — тоже пометим как доверенный
                trustedProxyAllowlist = setOf("127.0.0.1", "::1")
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "2001:db8::1234, ::1")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "uses Forwarded header when XFF is absent" {
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
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("Forwarded", "for=203.0.113.5;proto=https, for=127.0.0.1")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "cleans IPv4 with port and ignores 'unknown' token" {
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
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "203.0.113.5:4321, unknown, 127.0.0.1")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "accepts mixed-case IPv4-mapped IPv6 from True-Client-IP" {
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
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header("True-Client-IP", "::FfFf:203.0.113.5")
            }
            response.status shouldBe HttpStatusCode.OK
        }
    }
})

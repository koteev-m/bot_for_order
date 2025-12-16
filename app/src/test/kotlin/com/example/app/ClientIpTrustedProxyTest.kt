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

private val expectedVaryTrustedProxy = listOf(
    HttpHeaders.Authorization,
    HttpHeaders.XForwardedFor,
    HttpHeaders.Forwarded,
    "X-Real-IP",
    "CF-Connecting-IP",
    "True-Client-IP"
).joinToString(", ")

class ClientIpTrustedProxyTest : StringSpec({
    "ignores spoofed XFF when proxy is untrusted" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = null,
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
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.XForwardedFor, "10.0.0.5")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            response.headers[HttpHeaders.Vary] shouldBe expectedVaryTrustedProxy
        }
    }

    "uses XFF when remote is trusted proxy" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("10.0.0.0/8"),
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
                header(HttpHeaders.XForwardedFor, "10.0.0.5, 127.0.0.1")
            }

            response.status shouldBe HttpStatusCode.OK
        }
    }
})

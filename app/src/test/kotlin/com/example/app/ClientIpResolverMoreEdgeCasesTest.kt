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

class ClientIpResolverMoreEdgeCasesTest : StringSpec({
    "uses Forwarded with quoted bracketed IPv6 and port" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("2001:db8::/32"),
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
                header(HttpHeaders.Forwarded, """for=\"[2001:db8::1]:443\";proto=https, for=127.0.0.1""")
            }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "accepts IPv4-mapped IPv6 ::ffff: form" {
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
                header(HttpHeaders.XForwardedFor, "::ffff:203.0.113.5, 127.0.0.1")
            }
            resp.status shouldBe HttpStatusCode.OK
        }
    }
})

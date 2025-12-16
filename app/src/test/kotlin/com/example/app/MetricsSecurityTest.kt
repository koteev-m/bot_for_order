package com.example.app

import com.example.app.config.BasicAuth
import com.example.app.config.MetricsConfig
import com.example.app.config.HealthConfig
import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.redisson.api.RedissonClient
import org.redisson.api.redisnode.BaseRedisNodes
import org.redisson.api.redisnode.RedisNodes
import org.redisson.config.Config
import java.util.Base64

class MetricsSecurityTest : StringSpec({
    "rejects missing or bad basic auth" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet()
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
            missing.headers[HttpHeaders.WWWAuthenticate] shouldBe "Basic realm=\"metrics\""

            val bad = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encode("metrics:bad")}")
            }
            bad.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "serves metrics with correct content type when authorized" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("127.0.0.1")
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
                header(HttpHeaders.Authorization, "Basic ${encode("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "127.0.0.1")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType] shouldBe "text/plain; version=0.0.4; charset=utf-8"
            response.bodyAsText().contains("# TYPE") shouldBe true
        }
    }

    "blocks disallowed ip even with auth" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("10.0.0.0/8")
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
                header(HttpHeaders.Authorization, "Basic ${encode("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "203.0.113.10")
            }

            response.status shouldBe HttpStatusCode.Forbidden
        }
    }
})

private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())

private fun healthDeps(): Pair<Database, RedissonClient> {
    val database = mockk<Database>()
    val redisConfig = Config().apply { useSingleServer().address = "redis://localhost:6379" }
    val nodesGroup = mockk<BaseRedisNodes> { every { pingAll() } returns true }
    val redisson = mockk<RedissonClient> {
        every { config } returns redisConfig
        every { getRedisNodes(any<RedisNodes<BaseRedisNodes>>()) } returns nodesGroup
    }
    return database to redisson
}

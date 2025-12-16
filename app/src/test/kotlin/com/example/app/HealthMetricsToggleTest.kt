package com.example.app

import com.example.app.config.HealthConfig
import com.example.app.config.MetricsConfig
import com.example.app.config.RedisConfig
import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

class HealthMetricsToggleTest : StringSpec({
    "returns Prometheus metrics payload when enabled" {
        val database = mockk<Database>()
        val redisConfig = Config().apply { useSingleServer().address = "redis://localhost:6379" }
        val nodesGroup = mockk<BaseRedisNodes> { every { pingAll() } returns true }
        val redisson = mockk<RedissonClient> {
            every { config } returns redisConfig
            every { getRedisNodes(any<RedisNodes<BaseRedisNodes>>()) } returns nodesGroup
        }
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = null,
                ipAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                            encodeDefaults = false
                        }
                    )
                }
                install(Koin) {
                    modules(
                        module {
                            single { database }
                            single { redisson }
                        }
                    )
                }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics")

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body.isNotBlank() shouldBe true
            body.contains("# TYPE") shouldBe true
        }
    }

    "metrics route absent when prometheus disabled" {
        val database = mockk<Database>()
        val redisConfig = Config().apply { useSingleServer().address = "redis://localhost:6379" }
        val nodesGroup = mockk<BaseRedisNodes> { every { pingAll() } returns true }
        val redisson = mockk<RedissonClient> {
            every { config } returns redisConfig
            every { getRedisNodes(any<RedisNodes<BaseRedisNodes>>()) } returns nodesGroup
        }
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = false,
                basicAuth = null,
                ipAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                            encodeDefaults = false
                        }
                    )
                }
                install(Koin) {
                    modules(
                        module {
                            single { database }
                            single { redisson }
                        }
                    )
                }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }
})

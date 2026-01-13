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
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.redisson.api.RedissonClient
import org.redisson.api.redisnode.BaseRedisNodes
import org.redisson.api.redisnode.RedisNodes
import org.redisson.config.Config

class RedisHealthTest : StringSpec({
    "returns DOWN when redis ping fails" {
        mockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        val database = mockk<Database>()
        coEvery { newSuspendedTransaction<Any>(context = any(), db = any<Database>(), statement = any()) } coAnswers {
            val tx = mockk<Transaction>(relaxed = true)
            thirdArg<suspend Transaction.() -> Any>().invoke(tx)
        }
        val redisConfig = Config().apply { useSingleServer().address = "redis://localhost:6379" }
        val nodesGroup = mockk<BaseRedisNodes> { every { pingAll() } throws RuntimeException("redis down") }
        val redisson = mockk<RedissonClient> {
            every { config } returns redisConfig
            every { getRedisNodes(any<RedisNodes<BaseRedisNodes>>()) } returns nodesGroup
        }
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = null,
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 10)
        )

        try {
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
                    installBaseRoutes(cfg, null)
                }

                val response = client.get("/health")

                response.status shouldBe HttpStatusCode.ServiceUnavailable
                val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                payload["status"]?.jsonPrimitive?.content shouldBe "DOWN"
                val redisStatus = payload["checks"]
                    ?.jsonArray
                    ?.first { it.jsonObject["name"]?.jsonPrimitive?.content == "redis" }
                    ?.jsonObject
                    ?.get("status")
                    ?.jsonPrimitive
                    ?.content
                redisStatus shouldBe "DOWN"
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }
})

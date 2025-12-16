package com.example.app

import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class BuildEndpointTest : StringSpec({
    "returns build metadata with no-store cache header" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig()
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false })
                }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/build")
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            val contentType = response.headers[HttpHeaders.ContentType]
            contentType?.startsWith("application/json") shouldBe true

            val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            payload["version"]?.jsonPrimitive?.content?.isNotBlank() shouldBe true
            payload["commit"]?.jsonPrimitive?.content?.isNotBlank() shouldBe true
            payload["branch"]?.jsonPrimitive?.content?.isNotBlank() shouldBe true
        }
    }
})

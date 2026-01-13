package com.example.app

import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class SecurityHeadersTest : StringSpec({
    "health has no-store and nosniff" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig()
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        mockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        coEvery {
            newSuspendedTransaction<Any>(context = any(), db = any(), statement = any())
        } returns true
        try {
            testApplication {
                application {
                    install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    install(Koin) { modules(module { single { database }; single { redisson } }) }
                    routing { installBaseRoutes(cfg, registry) }
                }
                val resp = client.get("/health")
                resp.status shouldBe HttpStatusCode.OK
                resp.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                resp.headers["X-Content-Type-Options"] shouldBe "nosniff"
                resp.headers["X-Frame-Options"] shouldBe "DENY"
                resp.headers["Referrer-Policy"] shouldBe "no-referrer"
                resp.headers["Pragma"] shouldBe "no-cache"
                resp.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }

    "build has no-store and nosniff" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig()
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        mockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        coEvery {
            newSuspendedTransaction<Any>(context = any(), db = any(), statement = any())
        } returns true
        try {
            testApplication {
                application {
                    install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    install(Koin) { modules(module { single { database }; single { redisson } }) }
                    routing { installBaseRoutes(cfg, registry) }
                }
                val resp = client.get("/build")
                resp.status shouldBe HttpStatusCode.OK
                resp.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                resp.headers["X-Content-Type-Options"] shouldBe "nosniff"
                resp.headers["X-Frame-Options"] shouldBe "DENY"
                resp.headers["Referrer-Policy"] shouldBe "no-referrer"
                resp.headers["Pragma"] shouldBe "no-cache"
                resp.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }

    "non-observability endpoint has no common headers" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig()
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        mockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        coEvery {
            newSuspendedTransaction<Any>(context = any(), db = any(), statement = any())
        } returns true
        try {
            testApplication {
                application {
                    install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                    install(Koin) { modules(module { single { database }; single { redisson } }) }
                    routing {
                        installBaseRoutes(cfg, registry)
                        get("/dummy") { call.respondText("ok") }
                    }
                }
                val resp = client.get("/dummy")
                resp.status shouldBe HttpStatusCode.OK
                resp.headers[HttpHeaders.CacheControl] shouldBe null
                resp.headers["X-Content-Type-Options"] shouldBe null
                resp.headers["X-Frame-Options"] shouldBe null
                resp.headers["Referrer-Policy"] shouldBe null
                resp.headers["Pragma"] shouldBe null
                resp.headers["Content-Security-Policy"] shouldBe null
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }
})

package com.example.app

import com.example.app.config.HstsConfig
import com.example.app.config.SecurityConfig
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
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class HstsHeadersTest : StringSpec({
    "does not append hsts when disabled" {
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
                resp.headers["Strict-Transport-Security"] shouldBe null
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }

    "does not append hsts when enabled but no https" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            security = SecurityConfig(
                hsts = HstsConfig(
                    enabled = true,
                    maxAgeSeconds = 999,
                    includeSubdomains = true,
                    preload = false,
                )
            )
        )
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
                resp.headers["Strict-Transport-Security"] shouldBe null
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }

    "appends hsts when enabled via X-Forwarded-Proto" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            security = SecurityConfig(
                hsts = HstsConfig(
                    enabled = true,
                    maxAgeSeconds = 1_234,
                    includeSubdomains = false,
                    preload = true,
                )
            )
        )
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
                val resp = client.get("/health") {
                    header("X-Forwarded-Proto", "https")
                }
                resp.status shouldBe HttpStatusCode.OK
                resp.headers["Strict-Transport-Security"] shouldBe "max-age=1234; preload"
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }

    "appends hsts when enabled via Forwarded proto" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            security = SecurityConfig(
                hsts = HstsConfig(
                    enabled = true,
                    maxAgeSeconds = 9_999,
                    includeSubdomains = true,
                    preload = false,
                )
            )
        )
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
                val resp = client.get("/health") {
                    header(HttpHeaders.Forwarded, "for=1.2.3.4;proto=https")
                }
                resp.status shouldBe HttpStatusCode.OK
                resp.headers["Strict-Transport-Security"] shouldBe "max-age=9999; includeSubDomains"
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }

    "appends hsts with Forwarded proto quoted uppercase" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            security = SecurityConfig(
                hsts = HstsConfig(
                    enabled = true,
                    maxAgeSeconds = 7_777,
                    includeSubdomains = true,
                    preload = false,
                )
            )
        )
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
                val resp = client.get("/health") {
                    header(HttpHeaders.Forwarded, "for=1.2.3.4; proto=\"HTTPS\"")
                }
                resp.status shouldBe HttpStatusCode.OK
                resp.headers["Strict-Transport-Security"] shouldBe "max-age=7777; includeSubDomains"
            }
        } finally {
            unmockkStatic("org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt")
        }
    }
})

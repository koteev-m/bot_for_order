package com.example.app

import com.example.app.config.BasicAuth
import com.example.app.config.MetricsConfig
import com.example.app.config.HealthConfig
import com.example.app.observability.registerBuildInfoMeter
import com.example.app.routes.PRESET_VARY_TOKENS
import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.routing.intercept
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.Base64

private val metricsVaryTokens = listOf(
    HttpHeaders.Authorization,
    HttpHeaders.XForwardedFor,
    HttpHeaders.Forwarded,
    "X-Real-IP",
    "CF-Connecting-IP",
    "True-Client-IP"
)

private val expectedVary = metricsVaryTokens.joinToString(", ")

class MetricsSecurityTest : StringSpec({
    "rejects missing or bad basic auth" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val expectedRealm = cfg.metrics.basicRealm
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
            missing.headers[HttpHeaders.WWWAuthenticate] shouldBe "Basic realm=\"$expectedRealm\", charset=\"UTF-8\""
            missing.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            missing.headers[HttpHeaders.Vary] shouldBe expectedVary
            missing.headers["X-Content-Type-Options"] shouldBe "nosniff"
            missing.headers["X-Frame-Options"] shouldBe "DENY"
            missing.headers["Referrer-Policy"] shouldBe "no-referrer"
            missing.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
            missing.headers["Pragma"] shouldBe "no-cache"

            val bad = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:bad")}")
            }
            bad.status shouldBe HttpStatusCode.Unauthorized
            bad.headers[HttpHeaders.WWWAuthenticate] shouldBe "Basic realm=\"$expectedRealm\", charset=\"UTF-8\""
            bad.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            bad.headers[HttpHeaders.Vary] shouldBe expectedVary
            bad.headers["X-Content-Type-Options"] shouldBe "nosniff"
            bad.headers["X-Frame-Options"] shouldBe "DENY"
            bad.headers["Referrer-Policy"] shouldBe "no-referrer"
            bad.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
            bad.headers["Pragma"] shouldBe "no-cache"
        }
    }

    "preserves existing Vary values" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
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
                install(createApplicationPlugin(name = "presetVary") {
                    onCall { call ->
                        if (call.request.path() == "/metrics") {
                            call.attributes.put(PRESET_VARY_TOKENS, setOf("Accept-Encoding"))
                        }
                    }
                })
                installBaseRoutes(cfg, registry)
            }

            val resp = client.get("/metrics")
            resp.status shouldBe HttpStatusCode.Unauthorized
            val varyTokens = resp.headers[HttpHeaders.Vary]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

            varyTokens shouldBe (setOf("Accept-Encoding") + metricsVaryTokens.toSet())
        }
    }

    "merges Vary case-insensitively" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
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
                install(createApplicationPlugin(name = "presetVary") {
                    onCall { call ->
                        if (call.request.path() == "/metrics") {
                            call.attributes.put(PRESET_VARY_TOKENS, setOf("accept-encoding", "authorization"))
                        }
                    }
                })
                installBaseRoutes(cfg, registry)
            }

            val resp = client.get("/metrics")
            resp.status shouldBe HttpStatusCode.Unauthorized
            val varyTokens = resp.headers[HttpHeaders.Vary]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            varyTokens.any { it.equals(HttpHeaders.Authorization, ignoreCase = true) } shouldBe true
            val normalized = varyTokens.map { it.lowercase() }.toSet()
            normalized shouldBe (setOf("accept-encoding") + metricsVaryTokens.map { it.lowercase() }.toSet())
        }
    }

    "does not duplicate security headers" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
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
                install(Koin) {
                    modules(module { single { database }; single { redisson } })
                }
                routing { installBaseRoutes(cfg, registry) }
            }

            repeat(2) {
                val resp = client.get("/metrics")
                resp.status shouldBe HttpStatusCode.Unauthorized

                val singleHeaders = mapOf(
                    HttpHeaders.WWWAuthenticate to "Basic realm=\"${cfg.metrics.basicRealm}\", charset=\"UTF-8\"",
                    HttpHeaders.CacheControl to "no-store",
                    HttpHeaders.Vary to expectedVary,
                    "X-Content-Type-Options" to "nosniff",
                    "X-Frame-Options" to "DENY",
                    "Referrer-Policy" to "no-referrer",
                    "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'",
                    "Pragma" to "no-cache",
                    "Cross-Origin-Resource-Policy" to "same-origin",
                    "X-Permitted-Cross-Domain-Policies" to "none",
                    "Expires" to "0",
                )

                singleHeaders.forEach { (name, expected) ->
                    resp.headers[name] shouldBe expected
                    resp.headers.getAll(name)?.size shouldBe 1
                }
            }
        }
    }

    "serves metrics with correct content type when authorized" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = setOf("127.0.0.1"),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registerBuildInfoMeter(registry)

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
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "127.0.0.1")
            }

            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            response.headers[HttpHeaders.Vary] shouldBe expectedVary
            response.headers["X-Content-Type-Options"] shouldBe "nosniff"
            response.headers["X-Frame-Options"] shouldBe "DENY"
            response.headers["Referrer-Policy"] shouldBe "no-referrer"
            response.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
            response.headers["Pragma"] shouldBe "no-cache"
            val contentType = response.headers[HttpHeaders.ContentType] ?: error("missing content type")
            ContentType.parse(contentType).let {
                it.contentType shouldBe "text"
                it.contentSubtype shouldBe "plain"
                it.parameter("version") shouldBe "0.0.4"
            }
            val body = response.bodyAsText()
            body.contains("# TYPE") shouldBe true
            body.lineSequence().any { line ->
                val trimmed = line.trim()
                val buildLine = trimmed.startsWith("build_info{") || trimmed.startsWith("build{")
                val endsWithOne = trimmed.endsWith("} 1") || trimmed.endsWith("} 1.0")
                buildLine && endsWithOne
            } shouldBe true
        }
    }

    "blocks disallowed ip even with auth" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
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
                install(Koin) {
                    modules(module { single { database }; single { redisson } })
                }
                routing { installBaseRoutes(cfg, registry) }
            }

            val response = client.get("/metrics") {
                header(HttpHeaders.Authorization, "Basic ${encodeBasicAuth("metrics:secret")}")
                header(HttpHeaders.XForwardedFor, "203.0.113.10")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            response.headers[HttpHeaders.Vary] shouldBe expectedVary
            response.headers["X-Content-Type-Options"] shouldBe "nosniff"
            response.headers["X-Frame-Options"] shouldBe "DENY"
            response.headers["Referrer-Policy"] shouldBe "no-referrer"
            response.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
            response.headers["Pragma"] shouldBe "no-cache"
            response.headers[HttpHeaders.WWWAuthenticate] shouldBe null
        }
    }

    "rejects oversized basic auth blob" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
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

            // Build an oversized password to exceed MAX_BASIC_AUTH_DECODED after base64 decode
            val bigPass = "a".repeat(5000)
            val blob = "metrics:$bigPass"
            val auth = "Basic " + Base64.getEncoder().encodeToString(blob.toByteArray())

            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, auth)
            }
            resp.status shouldBe HttpStatusCode.Unauthorized
            // Still carries our security headers
            resp.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            resp.headers["X-Content-Type-Options"] shouldBe "nosniff"
        }
    }

    "rejects basic auth with control chars" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            // inject CRLF into password
            val dirtyPass = "sec\r\nret"
            val blob = "metrics:$dirtyPass"
            val auth = "Basic " + java.util.Base64.getEncoder().encodeToString(blob.toByteArray())

            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, auth)
            }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            resp.headers["X-Content-Type-Options"] shouldBe "nosniff"
        }
    }

    "honors user/pass length limits (boundary and overflow)" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("u".repeat(256), "p".repeat(2048)),
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            // boundary: exactly at limits -> OK (after IP allowlist bypass, use loopback)
            val okUser = "u".repeat(256)
            val okPass = "p".repeat(2048)
            val okAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("$okUser:$okPass".toByteArray())

            val ok = client.get("/metrics") {
                header(HttpHeaders.Authorization, okAuth)
                // allow local
                header(HttpHeaders.XForwardedFor, "127.0.0.1")
            }
            ok.status shouldBe HttpStatusCode.OK

            // overflow: +1 -> 401
            val badUser = "u".repeat(257)
            val badAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("$badUser:$okPass".toByteArray())

            val bad = client.get("/metrics") { header(HttpHeaders.Authorization, badAuth) }
            bad.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "respects decoded size boundary" {
        val (database, redisson) = healthDeps()
        // 1362 euros (3 bytes each) + "aa" (2 bytes) -> 4,088 bytes; with user+colon = 4,096 decoded bytes
        val boundaryPass = "€".repeat(1_362) + "aa"
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", boundaryPass),
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val blob = "metrics:$boundaryPass"
            val auth = "Basic " + Base64.getEncoder().encodeToString(blob.toByteArray())

            val resp = client.get("/metrics") {
                header(HttpHeaders.Authorization, auth)
            }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "rejects empty user or pass" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val emptyUser = "Basic " + Base64.getEncoder().encodeToString(":secret".toByteArray())
            val emptyPass = "Basic " + Base64.getEncoder().encodeToString("metrics:".toByteArray())

            val missingUserResp = client.get("/metrics") { header(HttpHeaders.Authorization, emptyUser) }
            missingUserResp.status shouldBe HttpStatusCode.Unauthorized

            val missingPassResp = client.get("/metrics") { header(HttpHeaders.Authorization, emptyPass) }
            missingPassResp.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "omits WWW-Authenticate when basic auth is disabled" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = null,
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registerBuildInfoMeter(registry)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val resp = client.get("/metrics")
            resp.status shouldBe HttpStatusCode.OK
            resp.headers[HttpHeaders.WWWAuthenticate] shouldBe null
        }
    }

    "uses configured basic auth realm" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                basicRealm = "obs",
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val resp = client.get("/metrics")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.headers[HttpHeaders.WWWAuthenticate] shouldBe "Basic realm=\"obs\", charset=\"UTF-8\""
        }
    }

    "escapes basic realm" {
        val (database, redisson) = healthDeps()
        val cfg = baseTestConfig(
            metrics = MetricsConfig(
                enabled = true,
                prometheusEnabled = true,
                basicAuth = BasicAuth("metrics", "secret"),
                basicRealm = "obs \" prod",
                ipAllowlist = emptySet(),
                trustedProxyAllowlist = emptySet()
            ),
            health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
        )
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }) }
                install(Koin) { modules(module { single { database }; single { redisson } }) }
                routing { installBaseRoutes(cfg, registry) }
            }

            val resp = client.get("/metrics")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.headers[HttpHeaders.WWWAuthenticate] shouldBe "Basic realm=\"obs \\\" prod\", charset=\"UTF-8\""
        }
    }
})

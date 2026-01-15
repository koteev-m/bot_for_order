package com.example.app

import com.example.app.config.BasicAuth
import com.example.app.config.HealthConfig
import com.example.app.config.MetricsConfig
import com.example.app.routes.installBaseRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.HttpRequestBuilder
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
        metricsResponseStatus(
            ipAllowlist = setOf("2001:db8::/32"),
            trustedProxyAllowlist = setOf("127.0.0.1", "::1")
        ) {
            header(HttpHeaders.XForwardedFor, "2001:db8::1234, ::1")
        } shouldBe HttpStatusCode.OK
    }

    "uses Forwarded header when XFF is absent" {
        metricsResponseStatus {
            header(HttpHeaders.Forwarded, "for=203.0.113.5;proto=https, for=127.0.0.1")
        } shouldBe HttpStatusCode.OK
    }

    "cleans IPv4 with port and ignores 'unknown' token" {
        metricsResponseStatus {
            header(HttpHeaders.XForwardedFor, "203.0.113.5:4321, unknown, 127.0.0.1")
        } shouldBe HttpStatusCode.OK
    }

    "ignores invalid token in XFF without exceptions" {
        metricsResponseStatus {
            header(HttpHeaders.XForwardedFor, "203.0.113.5, not-an-ip, 127.0.0.1")
        } shouldBe HttpStatusCode.OK
    }

    "ignores hex-only garbage token in XFF" {
        metricsResponseStatus {
            header(HttpHeaders.XForwardedFor, "203.0.113.5, deadbeef, 127.0.0.1")
        } shouldBe HttpStatusCode.OK
    }

    "ignores dotted hex garbage token in XFF" {
        metricsResponseStatus {
            header(HttpHeaders.XForwardedFor, "203.0.113.5, dead.beef, 127.0.0.1")
        } shouldBe HttpStatusCode.OK
    }

    "ignores dotted garbage token in XFF" {
        metricsResponseStatus {
            header(HttpHeaders.XForwardedFor, "203.0.113.5, ..., 127.0.0.1")
        } shouldBe HttpStatusCode.OK
    }

    "ignores colon-only garbage token in XFF" {
        metricsResponseStatus {
            header(HttpHeaders.XForwardedFor, "203.0.113.5, ::::, 127.0.0.1")
        } shouldBe HttpStatusCode.OK
    }

    "ignores invalid True-Client-IP token in fallback chain" {
        metricsResponseStatus {
            header(TRUE_CLIENT_IP, "not-an-ip")
            header(CF_CONNECTING_IP, "203.0.113.7")
        } shouldBe HttpStatusCode.OK
    }

    "accepts mixed-case IPv4-mapped IPv6 from True-Client-IP" {
        metricsResponseStatus {
            header(TRUE_CLIENT_IP, "::FfFf:203.0.113.5")
        } shouldBe HttpStatusCode.OK
    }

    "prefers True-Client-IP over X-Real-IP in fallback order" {
        metricsResponseStatus(
            ipAllowlist = setOf("203.0.113.10/32")
        ) {
            header(TRUE_CLIENT_IP, "203.0.113.10")
            header(X_REAL_IP, "203.0.113.11")
        } shouldBe HttpStatusCode.OK
    }

    "skips trusted True-Client-IP and falls back to CF-Connecting-IP" {
        metricsResponseStatus(
            trustedProxyAllowlist = setOf("127.0.0.1", "10.0.0.1")
        ) {
            header(TRUE_CLIENT_IP, "10.0.0.1")
            header(CF_CONNECTING_IP, "203.0.113.7")
        } shouldBe HttpStatusCode.OK
    }
})

private const val TRUE_CLIENT_IP = "True-Client-IP"
private const val CF_CONNECTING_IP = "CF-Connecting-IP"
private const val X_REAL_IP = "X-Real-IP"

private fun metricsResponseStatus(
    ipAllowlist: Set<String> = setOf("203.0.113.0/24"),
    trustedProxyAllowlist: Set<String> = setOf("127.0.0.1"),
    request: HttpRequestBuilder.() -> Unit = {}
): HttpStatusCode {
    val (database, redisson) = healthDeps()
    val cfg = baseTestConfig(
        metrics = MetricsConfig(
            enabled = true,
            prometheusEnabled = true,
            basicAuth = BasicAuth("metrics", "secret"),
            ipAllowlist = ipAllowlist,
            trustedProxyAllowlist = trustedProxyAllowlist
        ),
        health = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50)
    )
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    var status: HttpStatusCode? = null
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
            request()
        }
        status = response.status
    }

    return requireNotNull(status) { "Expected response status to be set in test application" }
}

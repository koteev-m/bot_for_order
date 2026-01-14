package com.example.app

import com.example.app.plugins.ObservabilityHeaders
import com.example.app.plugins.OBS_ENABLED
import com.example.app.plugins.OBS_VARY_TOKENS
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class ObservabilityHeadersTest : StringSpec({
    "does not normalize vary on non-observability endpoints" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf("accept-encoding" to "Accept-Encoding")
                }
                routing {
                    get("/plain") {
                        call.response.headers.append(HttpHeaders.Vary, "Accept-Encoding")
                        call.response.headers.append(HttpHeaders.Vary, "User-Agent")
                        call.respondText("ok")
                    }
                }
            }

            val response = client.get("/plain")
            val varyValues = response.headers.getAll(HttpHeaders.Vary).orEmpty()
            varyValues.size shouldBe 2
            varyValues.any { it.contains("Accept-Encoding") } shouldBe true
            varyValues.any { it.contains("User-Agent") } shouldBe true
        }
    }

    "normalizes canonical vary keys on configuration" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf("Accept-Encoding" to "Accept-Encoding")
                }
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(OBS_VARY_TOKENS, mutableSetOf("accept-encoding"))
                        call.respondText("ok")
                    }
                }
            }

            val response = client.get("/obs")
            val varyValues = response.headers[HttpHeaders.Vary]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()

            varyValues shouldBe setOf("Accept-Encoding")
        }
    }
})

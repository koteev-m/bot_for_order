package com.example.app

import com.example.app.plugins.ObservabilityHeaders
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
            response.headers.getAll(HttpHeaders.Vary) shouldBe listOf("Accept-Encoding", "User-Agent")
        }
    }
})

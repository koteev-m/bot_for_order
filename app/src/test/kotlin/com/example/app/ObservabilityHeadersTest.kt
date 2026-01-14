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

    "trims canonical vary keys on configuration" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf(" Accept-Encoding " to "Accept-Encoding")
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

    "falls back to token when canonical value is invalid" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf("accept-encoding" to "Accept-Encoding, invalid")
                }
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(OBS_VARY_TOKENS, mutableSetOf("Accept-Encoding"))
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

    "falls back to token when canonical value contains unicode" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf("accept-encoding" to "Ä")
                }
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(OBS_VARY_TOKENS, mutableSetOf("Accept-Encoding"))
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

    "keeps token when canonical mapping is misconfigured" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf("authorization" to "User-Agent")
                }
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(OBS_VARY_TOKENS, mutableSetOf("Authorization"))
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

            varyValues shouldBe setOf("Authorization")
        }
    }

    "ignores wildcard canonical mapping for non-wildcard key" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf("authorization" to "*")
                }
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(OBS_VARY_TOKENS, mutableSetOf("Authorization"))
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

            varyValues shouldBe setOf("Authorization")
        }
    }

    "replaces vary with wildcard when upstream sets vary star" {
        testApplication {
            application {
                install(ObservabilityHeaders)
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(OBS_VARY_TOKENS, mutableSetOf("Authorization"))
                        call.response.headers.append(HttpHeaders.Vary, "*")
                        call.respondText("ok")
                    }
                }
            }

            val response = client.get("/obs")
            response.headers[HttpHeaders.Vary] shouldBe "*"
            response.headers.getAll(HttpHeaders.Vary)?.size shouldBe 1
        }
    }

    "ignores invalid vary tokens from observability" {
        testApplication {
            application {
                install(ObservabilityHeaders)
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(
                            OBS_VARY_TOKENS,
                            mutableSetOf("Bad Token", "Accept-Encoding"),
                        )
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

    "ignores invalid vary tokens with control chars from observability" {
        testApplication {
            application {
                install(ObservabilityHeaders)
                routing {
                    get("/obs") {
                        call.attributes.put(OBS_ENABLED, true)
                        call.attributes.put(
                            OBS_VARY_TOKENS,
                            mutableSetOf("User-Agent\nBad", "Accept-Encoding"),
                        )
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

    "ignores empty canonical vary key" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf(" " to "User-Agent", "accept-encoding" to "Accept-Encoding")
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

    "ignores canonical vary mapping with invalid key" {
        testApplication {
            application {
                install(ObservabilityHeaders) {
                    canonicalVary = mapOf("Bad Key" to "Bad Key", "accept-encoding" to "Accept-Encoding")
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

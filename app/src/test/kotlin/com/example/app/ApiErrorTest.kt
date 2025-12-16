package com.example.app

import com.example.app.api.ErrorResponse
import com.example.app.api.installApiErrors
import com.example.app.api.respondApiError
import com.example.app.observability.REQUEST_ID_MDC_KEY
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory

class ApiErrorTest : StringSpec({
    "unknown route returns requestId" {
        testApplication {
            application {
                install(CallId) {
                    retrieveFromHeader(HttpHeaders.XRequestId)
                    generate { "generated-id" }
                    verify { it.isNotBlank() }
                    replyToHeader(HttpHeaders.XRequestId)
                }
                install(CallLogging) { callIdMdc(REQUEST_ID_MDC_KEY) }
                install(StatusPages) {
                    val log = LoggerFactory.getLogger("ApiErrorTest")
                    installApiErrors(log)
                    status(HttpStatusCode.NotFound) { call, status ->
                        call.respondApiError(
                            logger = log,
                            status = status,
                            message = status.description.lowercase().replace(" ", "_"),
                            warn = true
                        )
                    }
                }
                install(ServerContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                            encodeDefaults = false
                        }
                    )
                }
            }

            val client = createClient {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                            encodeDefaults = false
                        }
                    )
                }
            }

            val response = client.get("/unknown") {
                header(HttpHeaders.XRequestId, "req-404")
            }

            response.status shouldBe HttpStatusCode.NotFound
            val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            error.requestId shouldBe "req-404"
            error.error shouldBe "not_found"
        }
    }
})

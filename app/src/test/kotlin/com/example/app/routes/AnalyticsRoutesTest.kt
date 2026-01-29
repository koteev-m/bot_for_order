package com.example.app.routes

import com.example.app.api.AnalyticsEventRequest
import com.example.app.api.installApiErrors
import com.example.app.baseTestConfig
import com.example.app.security.TelegramInitDataVerifier
import com.example.app.security.installInitDataAuth
import com.example.app.testutil.InMemoryEventLogRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json

class AnalyticsRoutesTest : StringSpec({
    "analytics validates listingId and variantId length" {
        val cfg = baseTestConfig()
        val eventLogRepository = InMemoryEventLogRepository()
        val initDataVerifier = TelegramInitDataVerifier(cfg.telegram.shopToken, cfg.telegramInitData.maxAgeSeconds)

        testApplication {
            application {
                install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                val appLog = environment.log
                install(StatusPages) { installApiErrors(appLog) }
                routing {
                    route("/api") {
                        installInitDataAuth(initDataVerifier)
                        registerAnalyticsRoutes(eventLogRepository, cfg)
                    }
                }
            }

            val client = createClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val initData = buildInitData(cfg.telegram.shopToken, userId = 42L)
            val longValue = "x".repeat(65)

            val listingResponse = client.post("/api/analytics/event") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(
                    AnalyticsEventRequest(
                        eventType = "view",
                        listingId = longValue
                    )
                )
            }
            listingResponse.status shouldBe HttpStatusCode.BadRequest

            val variantResponse = client.post("/api/analytics/event") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Telegram-Init-Data", initData)
                setBody(
                    AnalyticsEventRequest(
                        eventType = "view",
                        variantId = longValue
                    )
                )
            }
            variantResponse.status shouldBe HttpStatusCode.BadRequest
        }
    }
})

private fun buildInitData(botToken: String, userId: Long): String {
    val authDate = Instant.now().epochSecond.toString()
    val queryId = "AAE-1"
    val userJson = """{"id":$userId,"first_name":"Test"}"""

    val dataCheckString = mapOf(
        "auth_date" to authDate,
        "query_id" to queryId,
        "user" to userJson
    ).toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }

    val secretKey = hmacSha256(
        "WebAppData".toByteArray(StandardCharsets.UTF_8),
        botToken.toByteArray(StandardCharsets.UTF_8)
    )
    val hash = hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8)).toHexLower()

    val encodedUser = URLEncoder.encode(userJson, StandardCharsets.UTF_8)
    return listOf(
        "auth_date=$authDate",
        "query_id=$queryId",
        "user=$encodedUser",
        "hash=$hash"
    ).joinToString("&")
}

private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg)
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

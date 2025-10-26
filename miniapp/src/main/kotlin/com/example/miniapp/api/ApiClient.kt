package com.example.miniapp.api

import com.example.miniapp.tg.TelegramBridge
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiClient(private val baseUrl: String = "") {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val client = HttpClient(Js) {
        install(ContentNegotiation) {
            json(json)
        }
        defaultRequest {
            val userId = TelegramBridge.userIdOrNull() ?: 1001L
            header("X-User-Id", userId.toString())
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun getItem(id: String): ItemResponse =
        client.get("$baseUrl/api/items/$id").body()

    suspend fun postOffer(request: OfferRequest): OfferDecisionResponse =
        client.post("$baseUrl/api/offer") { setBody(request) }.body()

    suspend fun postOrder(request: OrderCreateRequest): OrderCreateResponse =
        client.post("$baseUrl/api/orders") { setBody(request) }.body()
}

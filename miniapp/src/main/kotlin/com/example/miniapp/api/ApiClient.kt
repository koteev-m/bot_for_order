package com.example.miniapp.api

import com.example.miniapp.tg.TelegramBridge
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiClient(
    private val baseUrl: String = ""
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient(Js) {
        install(ContentNegotiation) { json(json) }
        install(DefaultRequest) {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            TelegramBridge.initDataRaw()?.let { header("X-Telegram-Init-Data", it) }
            TelegramBridge.userIdOrNull()?.let { header("X-User-Id", it.toString()) }
        }
    }

    suspend fun getItem(id: String): ItemResponse =
        client.get("$baseUrl/api/items/$id").body()

    suspend fun postOffer(req: OfferRequest): OfferDecisionResponse =
        client.post("$baseUrl/api/offer") { setBody(req) }.body()

    suspend fun acceptOffer(req: OfferAcceptRequest): OfferAcceptResponse =
        client.post("$baseUrl/api/offer/accept") { setBody(req) }.body()

    suspend fun postOrder(req: OrderCreateRequest): OrderCreateResponse =
        client.post("$baseUrl/api/orders") { setBody(req) }.body()

    suspend fun subscribePriceDrop(req: WatchlistSubscribeRequest): SimpleResponse =
        client.post("$baseUrl/api/watchlist") { setBody(req) }.body()
}

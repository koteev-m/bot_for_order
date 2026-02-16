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
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    suspend fun subscribeWatchlist(req: WatchlistSubscribeRequest): SimpleResponse =
        client.post("$baseUrl/api/watchlist") { setBody(req) }.body()

    suspend fun resolveLink(token: String): LinkResolveResponse =
        client.post("$baseUrl/api/link/resolve") { setBody(LinkResolveRequest(token)) }.body()

    suspend fun addToCartByToken(
        token: String,
        selectedVariantId: String?,
        idempotencyKey: String
    ): AddByTokenResult {
        val response = client.post("$baseUrl/api/cart/add_by_token") {
            header("Idempotency-Key", idempotencyKey)
            setBody(
                CartAddByTokenRequest(
                    token = token,
                    qty = 1,
                    selectedVariantId = selectedVariantId
                )
            )
        }
        return parseAddByTokenResult(response)
    }

    suspend fun removeCartLine(lineId: Long): SimpleResponse =
        client.post("$baseUrl/api/cart/update") {
            setBody(CartUpdateRequest(lineId = lineId, remove = true))
        }.body()

    suspend fun undoAdd(undoToken: String): SimpleResponse =
        client.post("$baseUrl/api/cart/undo") { setBody(CartUndoRequest(undoToken)) }.body()

    suspend fun getCart(): CartResponse {
        val response = client.get("$baseUrl/api/cart")
        val body = ensureSuccess(response)
        return json.decodeFromString(body)
    }

    suspend fun updateCartQty(lineId: Long, qty: Int): CartResponse {
        val payload = buildJsonObject {
            put("lineId", lineId)
            put("qty", qty)
        }
        return updateCart(payload)
    }

    suspend fun removeCartLineFromScreen(lineId: Long): CartResponse {
        val payload = buildJsonObject {
            put("lineId", lineId)
            put("remove", true)
        }
        return updateCart(payload)
    }

    suspend fun getAdminMe(): AdminMeResponse =
        client.get("$baseUrl/api/admin/me").body()

    suspend fun listAdminOrders(bucket: String, limit: Int, offset: Int): AdminOrdersPage =
        client.get("$baseUrl/api/admin/orders?bucket=$bucket&limit=$limit&offset=$offset").body()

    suspend fun getAdminOrder(orderId: String): AdminOrderCardResponse =
        client.get("$baseUrl/api/admin/orders/$orderId").body()

    suspend fun confirmPayment(orderId: String): PaymentSelectResponse =
        client.post("$baseUrl/api/admin/orders/$orderId/payment/confirm").body()

    suspend fun rejectPayment(orderId: String, reason: String): PaymentSelectResponse =
        client.post("$baseUrl/api/admin/orders/$orderId/payment/reject") {
            setBody(AdminPaymentRejectRequest(reason))
        }.body()

    suspend fun updateOrderStatus(orderId: String, req: AdminOrderStatusRequest): PaymentSelectResponse =
        client.post("$baseUrl/api/admin/orders/$orderId/status") { setBody(req) }.body()

    suspend fun getPaymentMethods(): List<AdminPaymentMethodDto> =
        client.get("$baseUrl/api/admin/settings/payment_methods").body()

    suspend fun updatePaymentMethods(req: AdminPaymentMethodsUpdateRequest): SimpleResponse =
        client.post("$baseUrl/api/admin/settings/payment_methods") { setBody(req) }.body()

    suspend fun getDeliveryMethod(): AdminDeliveryMethodDto =
        client.get("$baseUrl/api/admin/settings/delivery_method").body()

    suspend fun updateDeliveryMethod(req: AdminDeliveryMethodUpdateRequest): SimpleResponse =
        client.post("$baseUrl/api/admin/settings/delivery_method") { setBody(req) }.body()

    suspend fun getStorefronts(): List<AdminStorefrontDto> =
        client.get("$baseUrl/api/admin/settings/storefronts").body()

    suspend fun upsertStorefront(req: AdminStorefrontRequest): AdminStorefrontDto =
        client.post("$baseUrl/api/admin/settings/storefronts") { setBody(req) }.body()

    suspend fun getChannelBindings(): List<AdminChannelBindingDto> =
        client.get("$baseUrl/api/admin/settings/channel_bindings").body()

    suspend fun upsertChannelBinding(req: AdminChannelBindingRequest): AdminChannelBindingDto =
        client.post("$baseUrl/api/admin/settings/channel_bindings") { setBody(req) }.body()

    suspend fun publish(req: AdminPublishRequest): AdminPublishResponse =
        client.post("$baseUrl/api/admin/publications/publish") { setBody(req) }.body()

    private suspend fun parseAddByTokenResult(response: HttpResponse): AddByTokenResult {
        ensureSuccess(response)
        val jsonText = response.body<String>()
        val status = json.parseToJsonElement(jsonText)
            .jsonObject["status"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: error("cart_add_by_token missing status")

        return when (status) {
            "variant_required" -> {
                val parsed = json.decodeFromString<VariantRequiredResponse>(jsonText)
                VariantRequiredResult(parsed.listing, parsed.availableVariants, parsed.requiredOptions)
            }

            "ok" -> {
                val parsed = json.decodeFromString<CartAddResponse>(jsonText)
                AddByTokenResponse(parsed.undoToken, parsed.addedLineId)
            }

            else -> error("cart_add_by_token unexpected status: $status")
        }
    }

    private suspend fun updateCart(payload: JsonObject): CartResponse {
        val response = client.post("$baseUrl/api/cart/update") {
            setBody(payload)
        }
        val body = ensureSuccess(response)
        return json.decodeFromString(body)
    }

    private suspend fun ensureSuccess(response: HttpResponse): String {
        val body = response.body<String>()
        if (response.status.isSuccess()) {
            return body
        }
        val error = runCatching {
            json.decodeFromString<ErrorResponse>(body).error
        }.getOrNull()
        throw ApiClientException(
            status = response.status.value,
            error = error,
            message = error ?: "request_failed_${response.status.value}"
        )
    }
}

class ApiClientException(
    val status: Int,
    val error: String?,
    message: String
) : RuntimeException(message)

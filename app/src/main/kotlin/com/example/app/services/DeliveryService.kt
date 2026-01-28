package com.example.app.services

import com.example.app.api.ApiError
import com.example.app.config.AppConfig
import com.example.db.BuyerDeliveryProfileRepository
import com.example.db.MerchantDeliveryMethodsRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrdersRepository
import com.example.domain.BuyerDeliveryProfile
import com.example.domain.DeliveryMethodType
import com.example.domain.Order
import com.example.domain.OrderDelivery
import com.example.domain.OrderStatus
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DeliveryService(
    private val config: AppConfig,
    private val ordersRepository: OrdersRepository,
    private val merchantDeliveryMethodsRepository: MerchantDeliveryMethodsRepository,
    private val orderDeliveryRepository: OrderDeliveryRepository,
    private val buyerDeliveryProfileRepository: BuyerDeliveryProfileRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun setOrderDelivery(orderId: String, buyerId: Long, fields: JsonObject): OrderDelivery {
        val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
        ensureOwner(order, buyerId)
        if (LOCKED_STATUSES.contains(order.status)) {
            throw ApiError("delivery_update_not_allowed", HttpStatusCode.Conflict)
        }
        val method = merchantDeliveryMethodsRepository.getEnabledMethod(order.merchantId, DeliveryMethodType.CDEK_PICKUP_MANUAL)
            ?: throw ApiError("delivery_method_unavailable", HttpStatusCode.Conflict)
        val requiredFields = DeliveryFieldsCodec.parseRequiredFields(method.requiredFieldsJson)
        DeliveryFieldsCodec.validateFields(fields, requiredFields)
        val now = Instant.now(clock)
        val existing = orderDeliveryRepository.getByOrder(orderId)
        val createdAt = existing?.createdAt ?: now
        val delivery = OrderDelivery(
            orderId = orderId,
            type = DeliveryMethodType.CDEK_PICKUP_MANUAL,
            fieldsJson = DeliveryFieldsCodec.encodeFields(fields),
            createdAt = createdAt,
            updatedAt = now
        )
        orderDeliveryRepository.upsert(delivery)
        return orderDeliveryRepository.getByOrder(orderId) ?: delivery
    }

    suspend fun getBuyerDeliveryProfile(buyerId: Long): BuyerDeliveryProfile? {
        val merchantId = config.merchants.defaultMerchantId
        return buyerDeliveryProfileRepository.get(merchantId, buyerId)
    }

    suspend fun setBuyerDeliveryProfile(buyerId: Long, fields: JsonObject): BuyerDeliveryProfile {
        val merchantId = config.merchants.defaultMerchantId
        val method = merchantDeliveryMethodsRepository.getEnabledMethod(merchantId, DeliveryMethodType.CDEK_PICKUP_MANUAL)
            ?: throw ApiError("delivery_method_unavailable", HttpStatusCode.Conflict)
        val requiredFields = DeliveryFieldsCodec.parseRequiredFields(method.requiredFieldsJson)
        DeliveryFieldsCodec.validateFields(fields, requiredFields)
        val now = Instant.now(clock)
        val profile = BuyerDeliveryProfile(
            merchantId = merchantId,
            buyerUserId = buyerId,
            fieldsJson = DeliveryFieldsCodec.encodeFields(fields),
            updatedAt = now
        )
        buyerDeliveryProfileRepository.upsert(profile)
        return buyerDeliveryProfileRepository.get(merchantId, buyerId) ?: profile
    }

    private fun ensureOwner(order: Order, buyerId: Long) {
        if (order.userId != buyerId) {
            throw ApiError("forbidden", HttpStatusCode.Forbidden)
        }
    }

    companion object {
        private val LOCKED_STATUSES = setOf(OrderStatus.canceled, OrderStatus.shipped, OrderStatus.delivered)
    }
}

internal object DeliveryFieldsCodec {
    private val json = Json

    private const val MAX_KEYS = 50
    private const val MAX_KEY_LEN = 64
    private const val MAX_VALUE_LEN = 512
    private const val MAX_TOTAL_BYTES = 8_192

    fun parseRequiredFields(requiredFieldsJson: String): List<String> {
        val element = runCatching { json.parseToJsonElement(requiredFieldsJson) }
            .getOrElse { throw ApiError("delivery_method_misconfigured", HttpStatusCode.InternalServerError) }
        if (element !is JsonArray) {
            throw ApiError("delivery_method_misconfigured", HttpStatusCode.InternalServerError)
        }
        val keys = element.map { entry ->
            val primitive = entry as? JsonPrimitive
            val value = primitive?.takeIf { it.isString }?.content
            value ?: throw ApiError("delivery_method_misconfigured", HttpStatusCode.InternalServerError)
        }
        return keys
    }

    fun validateFields(fields: JsonObject, requiredFields: List<String>) {
        validateStructure(fields)
        requiredFields.forEach { key ->
            val value = fields[key]
            val primitive = value as? JsonPrimitive
            val text = primitive?.takeIf { it.isString }?.content?.trim()
            if (text.isNullOrEmpty()) {
                throw ApiError("delivery_required_field_missing: $key", HttpStatusCode.BadRequest)
            }
        }
    }

    fun encodeFields(fields: JsonObject): String {
        return json.encodeToString(fields)
    }

    fun decodeFields(fieldsJson: String): JsonObject {
        val element = runCatching { json.parseToJsonElement(fieldsJson) }
            .getOrElse { throw ApiError("invalid_delivery_fields", HttpStatusCode.InternalServerError) }
        return element as? JsonObject
            ?: throw ApiError("invalid_delivery_fields", HttpStatusCode.InternalServerError)
    }

    private fun validateStructure(fields: JsonObject) {
        if (fields.size > MAX_KEYS) {
            throw ApiError("invalid_delivery_fields", HttpStatusCode.BadRequest)
        }
        fields.forEach { (key, value) ->
            if (key.length > MAX_KEY_LEN) {
                throw ApiError("invalid_delivery_fields", HttpStatusCode.BadRequest)
            }
            if (!value.isValidPrimitive()) {
                throw ApiError("invalid_delivery_fields", HttpStatusCode.BadRequest)
            }
            val asString = when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
            if (asString.length > MAX_VALUE_LEN) {
                throw ApiError("invalid_delivery_fields", HttpStatusCode.BadRequest)
            }
        }
        val bytes = encodeFields(fields).toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_TOTAL_BYTES) {
            throw ApiError("invalid_delivery_fields", HttpStatusCode.BadRequest)
        }
    }

    private fun JsonElement.isValidPrimitive(): Boolean {
        if (this is JsonNull) return false
        return this is JsonPrimitive
    }
}

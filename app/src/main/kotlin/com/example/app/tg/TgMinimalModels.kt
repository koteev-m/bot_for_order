package com.example.app.tg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("shipping_query") val shippingQuery: TgShippingQuery? = null,
    @SerialName("pre_checkout_query") val preCheckoutQuery: TgPreCheckoutQuery? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val date: Long,
    val text: String? = null,
    val from: TgUser? = null,
    val chat: TgChat,
    @SerialName("media_group_id") val mediaGroupId: String? = null,
    val photo: List<TgPhotoSize>? = null,
    val video: TgVideo? = null,
    @SerialName("successful_payment") val successfulPayment: TgSuccessfulPayment? = null
)

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null
)

@Serializable
data class TgPhotoSize(
    @SerialName("file_id") val fileId: String,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class TgVideo(
    @SerialName("file_id") val fileId: String,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null
)

@Serializable
data class TgShippingQuery(
    val id: String,
    val from: TgUser,
    @SerialName("invoice_payload") val invoicePayload: String,
    @SerialName("shipping_address") val shippingAddress: TgShippingAddress
)

@Serializable
data class TgShippingAddress(
    @SerialName("country_code") val countryCode: String,
    @SerialName("state") val state: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("street_line1") val streetLine1: String? = null,
    @SerialName("street_line2") val streetLine2: String? = null,
    @SerialName("post_code") val postCode: String? = null
)

@Serializable
data class TgPreCheckoutQuery(
    val id: String,
    val from: TgUser,
    val currency: String,
    @SerialName("total_amount") val totalAmount: Long,
    @SerialName("invoice_payload") val invoicePayload: String
)

@Serializable
data class TgSuccessfulPayment(
    val currency: String,
    @SerialName("total_amount") val totalAmount: Long,
    @SerialName("invoice_payload") val invoicePayload: String,
    @SerialName("telegram_payment_charge_id") val telegramPaymentChargeId: String,
    @SerialName("provider_payment_charge_id") val providerPaymentChargeId: String
)

@Serializable
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage? = null,
    val data: String? = null
)

package com.example.app.api

import kotlinx.serialization.Serializable

@Serializable
data class ItemResponse(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val allowBargain: Boolean,
    val prices: DisplayPrices?,
    val invoiceCurrency: String,
    val media: List<ItemMediaResponse>,
    val variants: List<VariantResponse>
)

@Serializable
data class DisplayPrices(
    val baseCurrency: String,
    val baseAmountMinor: Long,
    val rub: Long? = null,
    val usd: Long? = null,
    val eur: Long? = null,
    val usdtTs: Long? = null
)

@Serializable
data class ItemMediaResponse(
    val fileId: String,
    val mediaType: String,
    val sortOrder: Int
)

@Serializable
data class VariantResponse(
    val id: String,
    val size: String? = null,
    val sku: String? = null,
    val stock: Int,
    val active: Boolean
)

@Serializable
data class OfferRequest(
    val itemId: String,
    val variantId: String? = null,
    val qty: Int = 1,
    val offerAmountMinor: Long
)

@Serializable
data class OfferDecisionResponse(
    val decision: String,
    val counterAmountMinor: Long? = null,
    val ttlSec: Long? = null
)

@Serializable
data class OrderCreateRequest(
    val itemId: String,
    val variantId: String? = null,
    val qty: Int = 1,
    val currency: String,
    val amountMinor: Long,
    val deliveryOption: String? = null,
    val addressJson: String? = null
)

@Serializable
data class OrderCreateResponse(
    val orderId: String,
    val status: String
)

@Serializable
data class OrderHistoryEntry(
    val status: String,
    val comment: String? = null,
    val ts: String
)

@Serializable
data class OrderCard(
    val orderId: String,
    val itemId: String,
    val variantId: String? = null,
    val qty: Int,
    val currency: String,
    val amountMinor: Long,
    val status: String,
    val updatedAt: String,
    val history: List<OrderHistoryEntry>
)

@Serializable
data class OrdersPage(
    val items: List<OrderCard>
)

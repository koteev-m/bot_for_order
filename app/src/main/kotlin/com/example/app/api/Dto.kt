package com.example.app.api

import com.example.domain.LinkAction
import com.example.domain.LinkButton
import kotlinx.serialization.SerialName
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
    val invoiceMinor: Long? = null,
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
data class OfferAcceptRequest(
    val offerId: String,
    val qty: Int? = null
)

@Serializable
data class OfferAcceptResponse(
    val orderId: String,
    val status: String
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

@Serializable
data class WatchlistSubscribeRequest(
    val itemId: String,
    val trigger: String = "price_drop",
    val variantId: String? = null,
    val targetMinor: Long? = null
)

@Serializable
data class SimpleResponse(
    val ok: Boolean = true
)

@Serializable
data class LinkResolveRequest(
    val token: String
)

@Serializable
data class ListingDto(
    val id: String,
    val title: String,
    val description: String,
    val status: String
)

@Serializable
data class LinkResolveRequiredOptions(
    @SerialName("variant_required")
    val variantRequired: Boolean,
    @SerialName("auto_variant_id")
    val autoVariantId: String? = null
)

@Serializable
data class LinkResolveVariant(
    val id: String,
    val size: String? = null,
    val sku: String? = null,
    val stock: Int,
    val active: Boolean,
    val available: Boolean
)

@Serializable
data class LinkResolveSource(
    val storefront: String,
    val channel: Long,
    val post: Int? = null,
    val button: LinkButton
)

@Serializable
data class LinkResolveResponse(
    val action: LinkAction,
    val listing: ListingDto,
    @SerialName("required_options")
    val requiredOptions: LinkResolveRequiredOptions,
    @SerialName("available_variants")
    val availableVariants: List<LinkResolveVariant>,
    val source: LinkResolveSource
)

@Serializable
data class CartAddByTokenRequest(
    val token: String,
    val qty: Int = 1,
    val selectedVariantId: String? = null
)

@Serializable
data class CartUndoRequest(
    val undoToken: String
)

@Serializable
data class CartLineDto(
    val lineId: Long,
    val listingId: String,
    val variantId: String? = null,
    val qty: Int,
    val priceSnapshotMinor: Long,
    val currency: String,
    val sourceStorefrontId: String,
    val sourceChannelId: Long,
    val sourcePostMessageId: Int? = null,
    val createdAt: String
)

@Serializable
data class CartDto(
    val id: Long,
    val merchantId: String,
    val buyerUserId: Long,
    val createdAt: String,
    val updatedAt: String,
    val items: List<CartLineDto>
)

@Serializable
data class CartAddResponse(
    val status: String = "ok",
    val undoToken: String,
    val addedLineId: Long,
    val cart: CartDto
)

@Serializable
data class CartVariantRequiredResponse(
    val status: String,
    val listing: ListingDto,
    @SerialName("available_variants")
    val availableVariants: List<LinkResolveVariant>,
    @SerialName("required_options")
    val requiredOptions: LinkResolveRequiredOptions
)

@Serializable
data class CartResponse(
    val status: String = "ok",
    val cart: CartDto
)

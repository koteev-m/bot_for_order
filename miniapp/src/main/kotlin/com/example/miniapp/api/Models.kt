package com.example.miniapp.api

import kotlinx.serialization.Serializable

@Serializable
data class ItemResponse(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val allowBargain: Boolean,
    val prices: DisplayPrices? = null,
    val invoiceCurrency: String,
    val media: List<ItemMediaResponse> = emptyList(),
    val variants: List<VariantResponse> = emptyList()
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
data class PaymentSelectResponse(
    val orderId: String,
    val status: String
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
    val variantRequired: Boolean,
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
data class LinkResolveResponse(
    val action: String,
    val listing: ListingDto,
    val requiredOptions: LinkResolveRequiredOptions,
    val availableVariants: List<LinkResolveVariant>
)

@Serializable
data class CartAddByTokenRequest(
    val token: String,
    val qty: Int = 1,
    val selectedVariantId: String? = null
)

@Serializable
data class CartAddResponse(
    val status: String = "ok",
    val undoToken: String,
    val addedLineId: Long
)

@Serializable
data class VariantRequiredResponse(
    val status: String,
    val listing: ListingDto,
    val availableVariants: List<LinkResolveVariant>,
    val requiredOptions: LinkResolveRequiredOptions
)

sealed interface AddByTokenResult

data class AddByTokenResponse(
    val undoToken: String,
    val addedLineId: Long
) : AddByTokenResult

data class VariantRequiredResult(
    val listing: ListingDto,
    val availableVariants: List<LinkResolveVariant>,
    val requiredOptions: LinkResolveRequiredOptions
) : AddByTokenResult

@Serializable
data class CartUpdateRequest(
    val lineId: Long,
    val remove: Boolean
)

@Serializable
data class CartUndoRequest(
    val undoToken: String
)

@Serializable
data class AdminMeResponse(
    val userId: Long,
    val role: String,
    val merchantId: String
)

@Serializable
data class AdminOrdersPage(
    val items: List<AdminOrderSummary>
)

@Serializable
data class AdminOrderSummary(
    val orderId: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val updatedAt: String,
    val buyerId: Long,
    val paymentMethodType: String? = null,
    val delivery: OrderDeliverySummary? = null
)

@Serializable
data class OrderDeliverySummary(
    val type: String,
    val fields: Map<String, String>
)

@Serializable
data class AdminOrderCardResponse(
    val orderId: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val buyerId: Long,
    val itemId: String? = null,
    val variantId: String? = null,
    val qty: Int? = null,
    val createdAt: String,
    val updatedAt: String,
    val lines: List<OrderLineDto> = emptyList(),
    val delivery: OrderDeliverySummary? = null,
    val payment: AdminPaymentInfo? = null
)

@Serializable
data class OrderLineDto(
    val listingId: String,
    val variantId: String? = null,
    val qty: Int,
    val priceSnapshotMinor: Long,
    val currency: String,
    val sourceStorefrontId: String? = null,
    val sourceChannelId: Long? = null,
    val sourcePostMessageId: Int? = null
)

@Serializable
data class AdminPaymentInfo(
    val methodType: String? = null,
    val claim: AdminPaymentClaim? = null,
    val attachments: List<AdminPaymentAttachment> = emptyList()
)

@Serializable
data class AdminPaymentClaim(
    val id: Long,
    val txid: String? = null,
    val comment: String? = null,
    val status: String,
    val createdAt: String
)

@Serializable
data class AdminPaymentAttachment(
    val id: Long,
    val presignedUrl: String,
    val mime: String,
    val size: Long
)

@Serializable
data class AdminOrderStatusRequest(
    val status: String,
    val comment: String? = null,
    val trackingCode: String? = null
)

@Serializable
data class AdminPaymentDetailsRequest(
    val text: String
)

@Serializable
data class AdminPaymentRejectRequest(
    val reason: String
)

@Serializable
data class AdminPaymentMethodDto(
    val type: String,
    val mode: String,
    val enabled: Boolean,
    val details: String? = null
)

@Serializable
data class AdminPaymentMethodsUpdateRequest(
    val methods: List<AdminPaymentMethodUpdate>
)

@Serializable
data class AdminPaymentMethodUpdate(
    val type: String,
    val mode: String,
    val enabled: Boolean,
    val details: String? = null
)

@Serializable
data class AdminDeliveryMethodDto(
    val type: String,
    val enabled: Boolean,
    val requiredFields: List<String>
)

@Serializable
data class AdminDeliveryMethodUpdateRequest(
    val enabled: Boolean,
    val requiredFields: List<String> = emptyList()
)

@Serializable
data class AdminStorefrontDto(
    val id: String,
    val name: String
)

@Serializable
data class AdminStorefrontRequest(
    val id: String,
    val name: String
)

@Serializable
data class AdminChannelBindingDto(
    val id: Long,
    val storefrontId: String,
    val channelId: Long
)

@Serializable
data class AdminChannelBindingRequest(
    val storefrontId: String,
    val channelId: Long
)

@Serializable
data class AdminPublishRequest(
    val itemId: String,
    val channelIds: List<Long>
)

@Serializable
data class AdminPublishResponse(
    val results: List<AdminPublishResult>
)

@Serializable
data class AdminPublishResult(
    val channelId: Long,
    val ok: Boolean,
    val error: String? = null
)

package com.example.domain

import com.example.domain.serialization.InstantIsoSerializer
import java.time.Instant
import kotlinx.serialization.Serializable

@Suppress("EnumNaming")
@Serializable
enum class ItemStatus { draft, active, sold }

@Serializable
data class Merchant(
    val id: String,
    val name: String,
    val paymentClaimWindowSeconds: Int,
    val paymentReviewWindowSeconds: Int
)

@Serializable
data class Storefront(
    val id: String,
    val merchantId: String,
    val name: String
)

@Serializable
data class ChannelBinding(
    val id: Long,
    val storefrontId: String,
    val channelId: Long,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant
)

@Suppress("EnumNaming")
@Serializable
enum class LinkAction { ADD, BUY }

@Suppress("EnumNaming")
@Serializable
enum class LinkButton { ADD, BUY }

@Serializable
data class LinkContext(
    val id: Long,
    val tokenHash: String,
    val merchantId: String,
    val storefrontId: String,
    val channelId: Long,
    val postMessageId: Int?,
    val listingId: String,
    val action: LinkAction,
    val button: LinkButton,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val revokedAt: Instant?,
    @Serializable(with = InstantIsoSerializer::class)
    val expiresAt: Instant?,
    val metadataJson: String
)

@Serializable
data class BargainRules(
    val minAcceptPct: Int,
    val minVisiblePct: Int,
    val maxCounters: Int,
    val cooldownSec: Int,
    val ttlSec: Int,
    val autoCounterStepPct: Int
)

@Serializable
data class Item(
    val id: String,
    val merchantId: String,
    val title: String,
    val description: String,
    val status: ItemStatus,
    val allowBargain: Boolean,
    val bargainRules: BargainRules? = null
)

@Serializable
data class ItemMedia(
    val id: Long,
    val itemId: String,
    val fileId: String,
    val mediaType: String,
    val sortOrder: Int
)

@Serializable
data class Variant(
    val id: String,
    val itemId: String,
    val size: String?,
    val sku: String?,
    val stock: Int,
    val active: Boolean
)

@Serializable
data class PricesDisplay(
    val itemId: String,
    val baseCurrency: String,
    val baseAmountMinor: Long,
    val invoiceCurrencyAmountMinor: Long?,
    val displayRub: Long?,
    val displayUsd: Long?,
    val displayEur: Long?,
    val displayUsdtTs: Long?,
    val fxSource: String?,
)

@Serializable
data class Post(
    val id: Long,
    val merchantId: String,
    val itemId: String,
    val channelMsgIds: List<Int>,
)

@Suppress("EnumNaming")
@Serializable
enum class OfferStatus { new, auto_accept, countered, accepted, declined, expired }

@Serializable
data class Offer(
    val id: String,
    val itemId: String,
    val variantId: String?,
    val userId: Long,
    val offerAmountMinor: Long,
    val status: OfferStatus,
    val countersUsed: Int,
    @Serializable(with = InstantIsoSerializer::class)
    val expiresAt: Instant?,
    val lastCounterAmount: Long?
)

@Suppress("EnumNaming")
@Serializable
enum class OrderStatus {
    pending,
    paid,
    fulfillment,
    shipped,
    delivered,
    canceled,
    AWAITING_PAYMENT_DETAILS,
    AWAITING_PAYMENT,
    PAYMENT_UNDER_REVIEW,
    PAID_CONFIRMED
}

@Serializable
enum class PaymentMethodType {
    MANUAL_CARD,
    MANUAL_CRYPTO
}

@Serializable
enum class PaymentMethodMode {
    AUTO,
    MANUAL_SEND
}

@Serializable
enum class PaymentClaimStatus {
    SUBMITTED,
    REJECTED,
    ACCEPTED
}

@Serializable
enum class OrderAttachmentKind {
    PAYMENT_PROOF
}

@Serializable
data class Order(
    val id: String,
    val merchantId: String,
    val userId: Long,
    val itemId: String?,
    val variantId: String?,
    val qty: Int?,
    val currency: String,
    val amountMinor: Long,
    val deliveryOption: String?,
    val addressJson: String?,
    val provider: String?,
    val providerChargeId: String?,
    val telegramPaymentChargeId: String? = null,
    val invoiceMessageId: Int? = null,
    val status: OrderStatus,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val paymentClaimedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class)
    val paymentDecidedAt: Instant? = null,
    val paymentMethodType: PaymentMethodType? = null,
    @Serializable(with = InstantIsoSerializer::class)
    val paymentMethodSelectedAt: Instant? = null
)

@Serializable
data class OrderLine(
    val orderId: String,
    val listingId: String,
    val variantId: String?,
    val qty: Int,
    val priceSnapshotMinor: Long,
    val currency: String,
    val sourceStorefrontId: String?,
    val sourceChannelId: Long?,
    val sourcePostMessageId: Int?
)

@Serializable
data class OrderStatusEntry(
    val id: Long,
    val orderId: String,
    val status: OrderStatus,
    val comment: String?,
    val actorId: Long?,
    @Serializable(with = InstantIsoSerializer::class)
    val ts: Instant
)

@Serializable
data class MerchantPaymentMethod(
    val merchantId: String,
    val type: PaymentMethodType,
    val mode: PaymentMethodMode,
    val detailsEncrypted: String?,
    val enabled: Boolean
)

@Serializable
data class OrderPaymentDetails(
    val orderId: String,
    val providedByAdminId: Long,
    val text: String,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant
)

@Serializable
data class OrderPaymentClaim(
    val id: Long,
    val orderId: String,
    val methodType: PaymentMethodType,
    val txid: String?,
    val comment: String?,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    val status: PaymentClaimStatus
)

@Serializable
data class OrderAttachment(
    val id: Long,
    val orderId: String,
    val claimId: Long?,
    val kind: OrderAttachmentKind,
    val storageKey: String?,
    val telegramFileId: String?,
    val mime: String,
    val size: Long,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant
)

@Serializable
data class Cart(
    val id: Long,
    val merchantId: String,
    val buyerUserId: Long,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val updatedAt: Instant
)

@Serializable
data class CartItem(
    val id: Long,
    val cartId: Long,
    val listingId: String,
    val variantId: String?,
    val qty: Int,
    val priceSnapshotMinor: Long,
    val currency: String,
    val sourceStorefrontId: String,
    val sourceChannelId: Long,
    val sourcePostMessageId: Int?,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant
)

@Serializable
enum class WatchTrigger { PRICE_DROP, RESTOCK }

@Serializable
data class WatchEntry(
    val id: Long,
    val userId: Long,
    val itemId: String,
    val variantId: String?,
    val triggerType: WatchTrigger,
    val params: String?
)

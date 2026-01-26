package com.example.domain

import com.example.domain.serialization.InstantIsoSerializer
import java.time.Instant
import kotlinx.serialization.Serializable

@Suppress("EnumNaming")
@Serializable
enum class ItemStatus { draft, active, sold }

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
    val itemId: String,
    val channelMsgIds: List<Int>,
)

@Suppress("EnumNaming")
@Serializable
enum class LinkButton { ADD, BUY, CART, PRODUCT }

@Suppress("EnumNaming")
@Serializable
enum class LinkAction { add_to_cart, buy_now, open_product }

@Serializable
data class LinkContext(
    val id: Long,
    val token: String,
    val merchantId: String?,
    val storefrontId: String?,
    val channelId: Long?,
    val postId: Long?,
    val button: LinkButton?,
    val action: LinkAction,
    val itemId: String?,
    val variantHint: String?,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val expiresAt: Instant?,
    @Serializable(with = InstantIsoSerializer::class)
    val revokedAt: Instant?,
    val metaJson: String?
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
enum class OrderStatus { pending, paid, fulfillment, shipped, delivered, canceled }

@Serializable
data class Order(
    val id: String,
    val userId: Long,
    val itemId: String,
    val variantId: String?,
    val qty: Int,
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
    val updatedAt: Instant
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

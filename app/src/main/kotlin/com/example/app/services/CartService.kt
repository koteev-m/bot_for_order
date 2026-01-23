package com.example.app.services

import com.example.app.api.ApiError
import com.example.app.api.CartDto
import com.example.app.api.CartLineDto
import com.example.app.api.LinkResolveRequiredOptions
import com.example.app.api.LinkResolveVariant
import com.example.app.api.ListingDto
import com.example.app.config.AppConfig
import com.example.db.CartItemsRepository
import com.example.db.CartsRepository
import com.example.db.ItemsRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.domain.Cart
import com.example.domain.CartItem
import com.example.domain.ItemStatus
import com.example.domain.Variant
import io.ktor.http.HttpStatusCode
import java.time.Instant

sealed interface CartAddResult {
    data class Added(
        val cart: CartDto,
        val undoToken: String,
        val addedLineId: Long
    ) : CartAddResult

    data class VariantRequired(
        val listing: ListingDto,
        val availableVariants: List<LinkResolveVariant>,
        val requiredOptions: LinkResolveRequiredOptions
    ) : CartAddResult
}

class CartService(
    private val config: AppConfig,
    private val linkContextService: LinkContextService,
    private val itemsRepository: ItemsRepository,
    private val variantsRepository: VariantsRepository,
    private val pricesDisplayRepository: PricesDisplayRepository,
    private val cartsRepository: CartsRepository,
    private val cartItemsRepository: CartItemsRepository,
    private val cartRedisStore: CartRedisStore,
    private val tokenHasher: LinkTokenHasher
) {
    suspend fun addByToken(
        buyerUserId: Long,
        token: String,
        qty: Int = 1,
        selectedVariantId: String? = null,
        now: Instant = Instant.now()
    ): CartAddResult {
        if (qty < 1) throw ApiError("invalid_request")

        val context = linkContextService.getByToken(token) ?: throw ApiError("not_found", HttpStatusCode.NotFound)
        if (context.revokedAt != null) throw ApiError("not_found", HttpStatusCode.NotFound)
        val expiresAt = context.expiresAt
        if (expiresAt != null && !expiresAt.isAfter(now)) throw ApiError("not_found", HttpStatusCode.NotFound)

        val item = itemsRepository.getById(context.listingId) ?: throw ApiError("not_found", HttpStatusCode.NotFound)
        if (item.merchantId != context.merchantId) throw ApiError("not_found", HttpStatusCode.NotFound)
        if (item.status != ItemStatus.active) throw ApiError("not_found", HttpStatusCode.NotFound)

        val variants = variantsRepository.listByItem(item.id)
        val purchasable = variants.filter { it.active && it.stock > 0 }
        val chosenVariantId = when {
            selectedVariantId != null -> {
                val available = purchasable.any { it.id == selectedVariantId }
                if (!available) throw ApiError("variant_not_available", HttpStatusCode.Conflict)
                selectedVariantId
            }
            variants.isEmpty() -> null
            purchasable.isEmpty() -> throw ApiError("out_of_stock", HttpStatusCode.Conflict)
            purchasable.size == 1 -> purchasable.single().id
            else -> {
                val listing = ListingDto(
                    id = item.id,
                    title = item.title,
                    description = item.description,
                    status = item.status.name
                )
                return CartAddResult.VariantRequired(
                    listing = listing,
                    availableVariants = purchasable.toResolveVariants(),
                    requiredOptions = LinkResolveRequiredOptions(
                        variantRequired = true,
                        autoVariantId = null
                    )
                )
            }
        }

        val priceSnapshot = resolvePriceSnapshot(item.id)
        val cart = cartsRepository.getOrCreate(context.merchantId, buyerUserId, now)
        val line = CartItem(
            id = 0,
            cartId = cart.id,
            listingId = item.id,
            variantId = chosenVariantId,
            qty = qty,
            priceSnapshotMinor = priceSnapshot.amountMinor,
            currency = priceSnapshot.currency,
            sourceStorefrontId = context.storefrontId,
            sourceChannelId = context.channelId,
            sourcePostMessageId = context.postMessageId,
            createdAt = now
        )
        val lineId = cartItemsRepository.create(line)
        val undoToken = tokenHasher.generateToken()
        val dedupKey = dedupKey(buyerUserId, context.merchantId, token)
        val dedupValue = "$undoToken:$lineId"
        val existing = cartRedisStore.tryRegisterDedup(dedupKey, dedupValue, config.cart.addDedupWindowSec)
        if (existing != null) {
            val parsed = parseDedupValue(existing)
            val lineWithCart = parsed?.let { cartItemsRepository.getLineWithCart(it.lineId) }
            val validDedup = lineWithCart != null &&
                lineWithCart.cart.buyerUserId == buyerUserId &&
                lineWithCart.cart.merchantId == context.merchantId
            if (parsed != null && validDedup) {
                cartItemsRepository.delete(lineId)
                val dedupCart = cartsRepository.getOrCreate(context.merchantId, buyerUserId, now)
                val dto = buildCartDto(dedupCart)
                return CartAddResult.Added(dto, parsed.undoToken, parsed.lineId)
            }
            cartRedisStore.overwriteDedup(dedupKey, dedupValue, config.cart.addDedupWindowSec)
        }

        try {
            cartRedisStore.saveUndo(undoToken, lineId, config.cart.undoTtlSec)
        } catch (e: Exception) {
            cartItemsRepository.delete(lineId)
            cartRedisStore.deleteDedupIfEquals(dedupKey, dedupValue)
            throw ApiError("undo_unavailable", HttpStatusCode.ServiceUnavailable, e)
        }
        cartsRepository.touch(cart.id, now)
        val dto = buildCartDto(cart, now)
        return CartAddResult.Added(dto, undoToken, lineId)
    }

    suspend fun update(
        buyerUserId: Long,
        lineId: Long,
        qty: Int?,
        variantId: String?,
        remove: Boolean,
        now: Instant = Instant.now()
    ): CartDto {
        return updateInternal(buyerUserId, lineId, qty, variantId, remove, variantUpdateRequested = true, now = now)
    }

    suspend fun updateWithOptions(
        buyerUserId: Long,
        lineId: Long,
        qty: Int?,
        variantId: String?,
        remove: Boolean,
        variantUpdateRequested: Boolean,
        now: Instant = Instant.now()
    ): CartDto {
        return updateInternal(buyerUserId, lineId, qty, variantId, remove, variantUpdateRequested, now)
    }

    suspend fun undo(
        buyerUserId: Long,
        undoToken: String,
        now: Instant = Instant.now()
    ): CartDto {
        val lineId = cartRedisStore.consumeUndo(undoToken) ?: throw ApiError("undo_expired", HttpStatusCode.NotFound)
        val lineWithCart = cartItemsRepository.getLineWithCart(lineId) ?: throw ApiError(
            "undo_expired",
            HttpStatusCode.NotFound
        )
        if (lineWithCart.cart.buyerUserId != buyerUserId) {
            throw ApiError("undo_expired", HttpStatusCode.NotFound)
        }
        if (!cartItemsRepository.delete(lineId)) {
            throw ApiError("undo_expired", HttpStatusCode.NotFound)
        }
        cartsRepository.touch(lineWithCart.cart.id, now)
        return buildCartDto(lineWithCart.cart, now)
    }

    suspend fun getCart(
        buyerUserId: Long,
        merchantId: String,
        now: Instant = Instant.now()
    ): CartDto {
        val cart = cartsRepository.getOrCreate(merchantId, buyerUserId, now)
        return buildCartDto(cart)
    }

    private suspend fun updateInternal(
        buyerUserId: Long,
        lineId: Long,
        qty: Int?,
        variantId: String?,
        remove: Boolean,
        variantUpdateRequested: Boolean,
        now: Instant
    ): CartDto {
        val lineWithCart = cartItemsRepository.getLineWithCart(lineId) ?: throw ApiError("not_found", HttpStatusCode.NotFound)
        if (lineWithCart.cart.buyerUserId != buyerUserId) throw ApiError("not_found", HttpStatusCode.NotFound)

        if (remove) {
            cartItemsRepository.delete(lineId)
            cartsRepository.touch(lineWithCart.cart.id, now)
            return buildCartDto(lineWithCart.cart, now)
        }

        if (qty != null) {
            if (qty < 1) throw ApiError("invalid_request")
            cartItemsRepository.updateQty(lineId, qty)
        }

        if (variantUpdateRequested) {
            val variants = variantsRepository.listByItem(lineWithCart.item.listingId)
            val updatedVariantId = when {
                variantId == null -> {
                    if (variants.isNotEmpty()) throw ApiError("variant_required", HttpStatusCode.Conflict)
                    null
                }
                else -> validateVariant(variants, variantId)
            }
            val priceSnapshot = resolvePriceSnapshot(lineWithCart.item.listingId)
            cartItemsRepository.updateVariant(lineId, updatedVariantId, priceSnapshot.amountMinor, priceSnapshot.currency)
        }

        if (qty != null || variantUpdateRequested) {
            cartsRepository.touch(lineWithCart.cart.id, now)
        }

        val updatedAt = if (qty != null || variantUpdateRequested) now else null
        return buildCartDto(lineWithCart.cart, updatedAt)
    }

    private fun validateVariant(variants: List<Variant>, variantId: String): String {
        val candidate = variants.firstOrNull { it.id == variantId }
            ?: throw ApiError("variant_not_available", HttpStatusCode.Conflict)
        if (!candidate.active || candidate.stock <= 0) {
            throw ApiError("variant_not_available", HttpStatusCode.Conflict)
        }
        return candidate.id
    }

    private suspend fun buildCartDto(cart: Cart, updatedAtOverride: Instant? = null): CartDto {
        val items = cartItemsRepository.listByCart(cart.id)
        return CartDto(
            id = cart.id,
            merchantId = cart.merchantId,
            buyerUserId = cart.buyerUserId,
            createdAt = cart.createdAt.toString(),
            updatedAt = (updatedAtOverride ?: cart.updatedAt).toString(),
            items = items.map { item ->
                CartLineDto(
                    lineId = item.id,
                    listingId = item.listingId,
                    variantId = item.variantId,
                    qty = item.qty,
                    priceSnapshotMinor = item.priceSnapshotMinor,
                    currency = item.currency,
                    sourceStorefrontId = item.sourceStorefrontId,
                    sourceChannelId = item.sourceChannelId,
                    sourcePostMessageId = item.sourcePostMessageId,
                    createdAt = item.createdAt.toString()
                )
            }
        )
    }

    private suspend fun resolvePriceSnapshot(itemId: String): PriceSnapshot {
        val prices = pricesDisplayRepository.get(itemId) ?: throw ApiError("price_unavailable", HttpStatusCode.Conflict)
        val invoiceAmount = prices.invoiceCurrencyAmountMinor
        val amount = invoiceAmount ?: prices.baseAmountMinor
        if (amount <= 0) throw ApiError("price_unavailable", HttpStatusCode.Conflict)
        val currency = if (invoiceAmount != null) {
            config.payments.invoiceCurrency.uppercase()
        } else {
            prices.baseCurrency.uppercase()
        }
        return PriceSnapshot(amountMinor = amount, currency = currency)
    }

    private fun dedupKey(buyerUserId: Long, merchantId: String, token: String): String {
        val tokenHash = tokenHasher.hash(token)
        return "cart:add:dedup:$buyerUserId:$merchantId:$tokenHash"
    }

    private fun parseDedupValue(value: String): DedupValue? {
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) return null
        val lineId = parts[1].toLongOrNull() ?: return null
        return DedupValue(parts[0], lineId)
    }

    private fun List<Variant>.toResolveVariants(): List<LinkResolveVariant> = map { variant ->
        LinkResolveVariant(
            id = variant.id,
            size = variant.size,
            sku = variant.sku,
            stock = variant.stock,
            active = variant.active,
            available = variant.active && variant.stock > 0
        )
    }

    private data class PriceSnapshot(
        val amountMinor: Long,
        val currency: String
    )

    private data class DedupValue(
        val undoToken: String,
        val lineId: Long
    )
}

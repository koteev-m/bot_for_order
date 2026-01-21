package com.example.app.services

import com.example.app.api.LinkResolveResponse
import com.example.app.api.LinkResolveRequiredOptions
import com.example.app.api.LinkResolveSource
import com.example.app.api.LinkResolveVariant
import com.example.app.api.ListingDto
import com.example.db.ItemsRepository
import com.example.db.VariantsRepository
import java.time.Instant

class LinkResolveException(message: String) : RuntimeException(message)

class LinkResolveService(
    private val linkContextService: LinkContextService,
    private val itemsRepository: ItemsRepository,
    private val variantsRepository: VariantsRepository
) {
    suspend fun resolve(token: String, now: Instant = Instant.now()): LinkResolveResponse {
        val context = linkContextService.getByToken(token) ?: throw LinkResolveException("not_found")
        if (context.revokedAt != null) throw LinkResolveException("not_found")
        if (context.expiresAt != null && !context.expiresAt.isAfter(now)) {
            throw LinkResolveException("not_found")
        }

        val listing = itemsRepository.getById(context.listingId) ?: throw LinkResolveException("not_found")
        if (listing.merchantId != context.merchantId) throw LinkResolveException("not_found")

        val variants = variantsRepository.listByItem(listing.id)
        val availableVariants = variants.map { variant ->
            LinkResolveVariant(
                id = variant.id,
                size = variant.size,
                sku = variant.sku,
                stock = variant.stock,
                active = variant.active,
                available = variant.active && variant.stock > 0
            )
        }

        val purchasable = variants.filter { it.active && it.stock > 0 }
        val requiredOptions = LinkResolveRequiredOptions(
            variantRequired = purchasable.size > 1,
            autoVariantId = purchasable.singleOrNull()?.id
        )

        return LinkResolveResponse(
            action = context.action,
            listing = ListingDto(
                id = listing.id,
                title = listing.title,
                description = listing.description,
                status = listing.status.name
            ),
            requiredOptions = requiredOptions,
            availableVariants = availableVariants,
            source = LinkResolveSource(
                storefront = context.storefrontId,
                channel = context.channelId,
                post = context.postMessageId,
                button = context.button
            )
        )
    }
}

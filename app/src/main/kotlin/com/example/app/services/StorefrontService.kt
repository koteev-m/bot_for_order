package com.example.app.services

import com.example.db.ChannelBindingsRepository
import com.example.db.MerchantsRepository
import com.example.db.StorefrontsRepository
import com.example.domain.ChannelBinding
import com.example.domain.Storefront
import java.time.Instant
import java.util.UUID

class StorefrontService(
    private val merchantsRepository: MerchantsRepository,
    private val storefrontsRepository: StorefrontsRepository,
    private val channelBindingsRepository: ChannelBindingsRepository
) {
    suspend fun createStorefront(merchantId: String, name: String): Storefront {
        requireNotNull(merchantsRepository.getById(merchantId)) { "merchant not found: $merchantId" }
        val storefront = Storefront(
            id = UUID.randomUUID().toString(),
            merchantId = merchantId,
            name = name
        )
        storefrontsRepository.create(storefront)
        return storefront
    }

    suspend fun bindChannel(storefrontId: String, channelId: Long): ChannelBinding {
        requireNotNull(storefrontsRepository.getById(storefrontId)) { "storefront not found: $storefrontId" }
        val createdAt = Instant.now()
        val id = channelBindingsRepository.bind(storefrontId, channelId, createdAt)
        return ChannelBinding(
            id = id,
            storefrontId = storefrontId,
            channelId = channelId,
            createdAt = createdAt
        )
    }
}

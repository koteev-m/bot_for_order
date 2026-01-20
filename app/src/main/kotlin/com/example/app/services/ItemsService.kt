package com.example.app.services

import com.example.app.config.MerchantsConfig
import com.example.db.ItemsRepository
import com.example.domain.Item
import com.example.domain.ItemStatus
import java.util.UUID

class ItemsService(
    private val itemsRepository: ItemsRepository,
    private val merchantsConfig: MerchantsConfig
) {
    suspend fun createDraft(title: String, description: String): String {
        val id = UUID.randomUUID().toString()
        val item = Item(
            id = id,
            merchantId = merchantsConfig.defaultMerchantId,
            title = title,
            description = description,
            status = ItemStatus.draft,
            allowBargain = false,
            bargainRules = null
        )
        itemsRepository.create(item)
        return id
    }
}

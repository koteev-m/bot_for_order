package com.example.app.routes

import com.example.app.api.ApiError

inline fun ensure(condition: Boolean, message: () -> String) {
    if (!condition) {
        throw ApiError(message())
    }
}

suspend fun findVariantForItem(
    variantId: String,
    itemId: String,
    variantsRepo: com.example.db.VariantsRepository
): com.example.domain.Variant {
    val variants = variantsRepo.listByItem(itemId)
    return variants.firstOrNull { it.id == variantId }
        ?: throw ApiError("variantId does not belong to item")
}

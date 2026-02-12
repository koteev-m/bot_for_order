package com.example.app.services

import com.example.app.api.ApiError
import io.ktor.http.HttpStatusCode

object QuickAddRequestValidation {
    private const val MAX_TOKEN_LENGTH = 512
    private const val MAX_LISTING_ID_LENGTH = 64
    private const val MAX_VARIANT_ID_LENGTH = 64

    fun normalizeToken(raw: String): String {
        val token = raw.trim()
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) {
            throw ApiError("invalid_token", HttpStatusCode.BadRequest)
        }
        return token
    }

    fun validateListingId(listingId: String) {
        if (listingId.isBlank() || listingId.length > MAX_LISTING_ID_LENGTH) {
            throw ApiError("invalid_request", HttpStatusCode.BadRequest)
        }
    }

    fun validateVariantId(variantId: String?) {
        if (variantId != null && (variantId.isBlank() || variantId.length > MAX_VARIANT_ID_LENGTH)) {
            throw ApiError("invalid_request", HttpStatusCode.BadRequest)
        }
    }
}

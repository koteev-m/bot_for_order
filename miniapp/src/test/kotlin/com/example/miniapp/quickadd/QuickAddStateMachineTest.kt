package com.example.miniapp.quickadd

import com.example.miniapp.api.LinkResolveRequiredOptions
import com.example.miniapp.api.LinkResolveResponse
import com.example.miniapp.api.LinkResolveVariant
import com.example.miniapp.api.ListingDto
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickAddStateMachineTest {

    @Test
    fun autoSelectsSingleAvailableVariant() {
        val response = linkResolve(
            required = true,
            variants = listOf(
                variant(id = "v1", available = true),
                variant(id = "v2", available = false)
            )
        )

        val result = evaluateQuickAddState(response)

        assertEquals(QuickAddStateKind.AUTO_ADD, result.kind)
        assertEquals("v1", result.selectedVariantId)
    }

    @Test
    fun asksVariantWhenMultipleAvailable() {
        val response = linkResolve(
            required = true,
            variants = listOf(
                variant(id = "v1", available = true),
                variant(id = "v2", available = true)
            )
        )

        val result = evaluateQuickAddState(response)

        assertEquals(QuickAddStateKind.NEED_VARIANT, result.kind)
    }

    private fun linkResolve(required: Boolean, variants: List<LinkResolveVariant>): LinkResolveResponse {
        return LinkResolveResponse(
            action = "ADD",
            listing = ListingDto(id = "l1", title = "T", description = "D", status = "active"),
            requiredOptions = LinkResolveRequiredOptions(variantRequired = required),
            availableVariants = variants
        )
    }

    private fun variant(id: String, available: Boolean): LinkResolveVariant {
        return LinkResolveVariant(
            id = id,
            size = null,
            sku = null,
            stock = if (available) 1 else 0,
            active = true,
            available = available
        )
    }
}

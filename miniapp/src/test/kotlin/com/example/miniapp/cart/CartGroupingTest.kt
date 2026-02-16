package com.example.miniapp.cart

import com.example.miniapp.api.CartLineDto
import kotlin.test.Test
import kotlin.test.assertEquals

class CartGroupingTest {

    @Test
    fun groupsByStorefrontAndCalculatesSubtotal() {
        val grouped = groupCartLinesByStorefront(
            listOf(
                line(lineId = 1, storefrontId = "s2", qty = 2, price = 1000),
                line(lineId = 2, storefrontId = "s1", qty = 1, price = 700),
                line(lineId = 3, storefrontId = "s2", qty = 3, price = 1000)
            )
        )

        assertEquals(listOf("s1", "s2"), grouped.map { it.storefrontId })
        assertEquals(700L, grouped.first { it.storefrontId == "s1" }.subtotals.single().amountMinor)
        assertEquals(5000L, grouped.first { it.storefrontId == "s2" }.subtotals.single().amountMinor)
    }

    @Test
    fun buildsTotalPerCurrency() {
        val total = buildCartTotal(
            listOf(
                line(lineId = 1, storefrontId = "s1", qty = 2, price = 1000, currency = "rub"),
                line(lineId = 2, storefrontId = "s1", qty = 1, price = 300, currency = "USD")
            )
        )

        assertEquals(2, total.size)
        assertEquals("RUB", total[0].currency)
        assertEquals(2000L, total[0].amountMinor)
        assertEquals("USD", total[1].currency)
        assertEquals(300L, total[1].amountMinor)
    }

    private fun line(
        lineId: Long,
        storefrontId: String,
        qty: Int,
        price: Long,
        currency: String = "RUB"
    ): CartLineDto {
        return CartLineDto(
            lineId = lineId,
            listingId = "item-$lineId",
            variantId = null,
            qty = qty,
            priceSnapshotMinor = price,
            currency = currency,
            sourceStorefrontId = storefrontId,
            sourceChannelId = 1,
            sourcePostMessageId = null,
            createdAt = "2026-01-01T00:00:0${lineId}Z"
        )
    }
}

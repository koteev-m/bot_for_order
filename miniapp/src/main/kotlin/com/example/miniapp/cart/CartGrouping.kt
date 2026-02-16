package com.example.miniapp.cart

import com.example.miniapp.api.CartLineDto

data class MoneySubtotal(
    val currency: String,
    val amountMinor: Long
)

data class CartStorefrontGroup(
    val storefrontId: String,
    val lines: List<CartLineDto>,
    val subtotals: List<MoneySubtotal>
)

fun groupCartLinesByStorefront(lines: List<CartLineDto>): List<CartStorefrontGroup> {
    return lines.groupBy { it.sourceStorefrontId }
        .entries
        .sortedBy { it.key }
        .map { entry ->
            CartStorefrontGroup(
                storefrontId = entry.key,
                lines = entry.value.sortedBy { it.createdAt },
                subtotals = buildSubtotals(entry.value)
            )
        }
}

fun buildCartTotal(lines: List<CartLineDto>): List<MoneySubtotal> = buildSubtotals(lines)

private fun buildSubtotals(lines: List<CartLineDto>): List<MoneySubtotal> {
    return lines.groupBy { it.currency.uppercase() }
        .entries
        .sortedBy { it.key }
        .map { entry ->
            MoneySubtotal(
                currency = entry.key,
                amountMinor = entry.value.sumOf { it.priceSnapshotMinor * it.qty }
            )
        }
}

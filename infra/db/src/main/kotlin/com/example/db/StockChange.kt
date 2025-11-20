package com.example.db

data class StockChange(
    val variantId: String,
    val itemId: String,
    val oldStock: Int,
    val newStock: Int
) {
    val restocked: Boolean get() = oldStock <= 0 && newStock > 0
}

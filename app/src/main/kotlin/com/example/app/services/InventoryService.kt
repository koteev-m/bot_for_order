package com.example.app.services

import com.example.db.StockChange
import com.example.db.VariantsRepository
import org.slf4j.LoggerFactory

class InventoryService(
    private val variantsRepository: VariantsRepository,
    private val restockAlertService: RestockAlertService
) {

    private val log = LoggerFactory.getLogger(InventoryService::class.java)

    suspend fun setStock(variantId: String, newStock: Int): InventorySetStockResult {
        require(newStock >= 0) { "stock must be >= 0" }
        val change = variantsRepository.setStock(variantId, newStock)
            ?: throw IllegalArgumentException("variant_not_found")
        val notified = if (change.restocked) {
            log.info(
                "restock_detected item={} variant={} old={} new={}",
                change.itemId,
                change.variantId,
                change.oldStock,
                change.newStock
            )
            restockAlertService.dispatch(change.itemId, change.variantId)
        } else {
            0
        }
        return InventorySetStockResult(change, notified)
    }
}

data class InventorySetStockResult(
    val change: StockChange,
    val notifiedSubscribers: Int
)

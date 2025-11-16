package com.example.app.services

import com.example.app.config.AppConfig
import com.example.db.ItemsRepository
import com.example.db.PricesDisplayRepository
import com.example.domain.DisplayPriceService
import com.example.domain.FxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class DisplayPriceServiceImpl(
    private val cfg: AppConfig,
    private val fxService: FxService,
    private val itemsRepository: ItemsRepository,
    private val pricesDisplayRepository: PricesDisplayRepository
) : DisplayPriceService {

    private val log = LoggerFactory.getLogger(DisplayPriceServiceImpl::class.java)
    private val displayCurrencies = cfg.fx.displayCurrencies.map(String::uppercase).toSet()

    override suspend fun recomputeItem(itemId: String) {
        val prices = pricesDisplayRepository.get(itemId) ?: return
        val snapshot = fxService.current()
        val baseCurrency = prices.baseCurrency.uppercase()
        val baseAmount = prices.baseAmountMinor
        val amounts = mutableMapOf<String, Long?>()

        displayCurrencies.forEach { currency ->
            val target = currency.uppercase()
            val converted = if (target == baseCurrency) {
                baseAmount
            } else {
                runCatching { fxService.convert(baseAmount, baseCurrency, target, snapshot.scale) }
                    .onFailure {
                        log.warn(
                            "FX convert failed for item {} {}->{}: {}",
                            itemId,
                            baseCurrency,
                            target,
                            it.message
                        )
                    }
                    .getOrNull()
            }
            amounts[target] = converted
        }

        val updated = prices.copy(
            displayRub = amounts["RUB"],
            displayUsd = amounts["USD"],
            displayEur = amounts["EUR"],
            displayUsdtTs = amounts["USDT_TS"],
            fxSource = snapshot.source
        )
        pricesDisplayRepository.upsert(updated)
    }

    override suspend fun recomputeAllActive() = withContext(Dispatchers.IO) {
        itemsRepository.listActive().forEach { item ->
            recomputeItem(item.id)
        }
    }
}

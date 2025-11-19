package com.example.app.services

import com.example.app.config.AppConfig
import com.example.db.ItemsRepository
import com.example.db.PricesDisplayRepository
import com.example.domain.DisplayPriceService
import com.example.domain.FxService
import com.example.domain.watchlist.PriceDropNotifier
import com.example.domain.watchlist.WatchlistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class DisplayPriceServiceImpl(
    private val cfg: AppConfig,
    private val fxService: FxService,
    private val itemsRepository: ItemsRepository,
    private val pricesDisplayRepository: PricesDisplayRepository,
    private val watchlistRepository: WatchlistRepository,
    private val priceDropNotifier: PriceDropNotifier
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
            val converted = convertAmount(itemId, baseAmount, baseCurrency, target, snapshot.scale)
            amounts[target] = converted
        }

        val invoiceCurrency = cfg.payments.invoiceCurrency.uppercase()
        val invoiceAmount = convertAmount(itemId, baseAmount, baseCurrency, invoiceCurrency, snapshot.scale)

        val updated = prices.copy(
            invoiceCurrencyAmountMinor = invoiceAmount,
            displayRub = amounts["RUB"],
            displayUsd = amounts["USD"],
            displayEur = amounts["EUR"],
            displayUsdtTs = amounts["USDT_TS"],
            fxSource = snapshot.source
        )
        maybeNotifyPriceDrop(itemId, prices.invoiceCurrencyAmountMinor, invoiceAmount)
        pricesDisplayRepository.upsert(updated)
    }

    override suspend fun recomputeAllActive() = withContext(Dispatchers.IO) {
        itemsRepository.listActive().forEach { item ->
            recomputeItem(item.id)
        }
    }

    private fun convertAmount(
        itemId: String,
        amountMinor: Long,
        fromCurrency: String,
        targetCurrency: String,
        scale: Int
    ): Long? {
        if (targetCurrency == fromCurrency) {
            return amountMinor
        }
        return runCatching { fxService.convert(amountMinor, fromCurrency, targetCurrency, scale) }
            .onFailure {
                log.warn(
                    "FX convert failed for item {} {}->{}: {}",
                    itemId,
                    fromCurrency,
                    targetCurrency,
                    it.message
                )
            }
            .getOrNull()
    }

    private suspend fun maybeNotifyPriceDrop(
        itemId: String,
        previousInvoice: Long?,
        newInvoice: Long?
    ) {
        val shouldCheck = cfg.server.watchlistPriceDropEnabled &&
            previousInvoice != null &&
            newInvoice != null &&
            newInvoice < previousInvoice
        if (!shouldCheck) {
            return
        }
        val absDrop = previousInvoice!! - newInvoice!!
        val relDrop = if (previousInvoice > 0) {
            (absDrop * 100.0) / previousInvoice
        } else {
            Double.POSITIVE_INFINITY
        }
        val thresholdsMet = absDrop >= cfg.server.priceDropMinAbsMinor &&
            relDrop >= cfg.server.priceDropMinRelPct
        if (!thresholdsMet) {
            return
        }
        val eligible = watchlistRepository
            .listPriceDropByItem(itemId)
            .filter { sub ->
                val target = sub.targetMinor
                target == null || newInvoice <= target
            }
        if (eligible.isNotEmpty()) {
            log.info(
                "price_drop_detected item={} invoice_old={} invoice_new={} subs={}",
                itemId,
                previousInvoice,
                newInvoice,
                eligible.size
            )
            eligible.forEach { sub ->
                runCatching {
                    priceDropNotifier.notify(sub.userId, itemId, newInvoice)
                }.onFailure {
                    log.warn("price_drop_notify_failed item={} cause={}", itemId, it.message)
                }
            }
        }
    }
}

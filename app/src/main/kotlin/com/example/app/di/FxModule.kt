package com.example.app.di

import com.example.app.config.AppConfig
import com.example.app.services.DisplayPriceServiceImpl
import com.example.app.services.FxServiceStub
import com.example.domain.DisplayPriceService
import com.example.domain.FxService
import com.example.domain.watchlist.PriceDropNotifier
import com.example.domain.watchlist.WatchlistRepository
import org.koin.dsl.module

fun fxModule(cfg: AppConfig) = module {
    single<FxService> {
        val currencies = buildSet {
            addAll(cfg.fx.displayCurrencies)
            add(cfg.payments.invoiceCurrency)
            addAll(listOf("USD", "EUR", "RUB", "USDT_TS"))
        }.map(String::uppercase).toSet()
        FxServiceStub(currencies)
    }

    single<DisplayPriceService> {
        DisplayPriceServiceImpl(
            cfg = cfg,
            fxService = get(),
            itemsRepository = get(),
            pricesDisplayRepository = get(),
            watchlistRepository = get<WatchlistRepository>(),
            priceDropNotifier = get<PriceDropNotifier>()
        )
    }
}

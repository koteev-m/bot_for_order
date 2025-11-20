package com.example.app.di

import com.example.app.config.AppConfig
import com.example.app.services.InventoryService
import com.example.app.services.PriceDropNotifierImpl
import com.example.app.services.RestockAlertService
import com.example.app.services.RestockNotifierImpl
import com.example.bots.TelegramClients
import com.example.domain.watchlist.PriceDropNotifier
import com.example.domain.watchlist.RestockNotifier
import com.example.redis.RedisClientFactory
import org.koin.dsl.module
import org.redisson.api.RedissonClient

fun appModule(config: AppConfig) = module {
    single<AppConfig> { config }

    single { TelegramClients(config.telegram.adminToken, config.telegram.shopToken) }

    single<RedissonClient> { RedisClientFactory.create(config.redis.url) }

    single<PriceDropNotifier> { PriceDropNotifierImpl(config, get(), get()) }
    single<RestockNotifier> { RestockNotifierImpl(config, get(), get(), get()) }
    single { RestockAlertService(get(), get(), config.server.watchlistRestockEnabled) }
    single { InventoryService(get(), get()) }
}

package com.example.app.di

import com.example.app.config.AppConfig
import com.example.app.services.CartRedisStore
import com.example.app.services.CartRedisStoreRedisson
import com.example.app.services.CartService
import com.example.app.services.InventoryService
import com.example.app.services.LinkContextService
import com.example.app.services.LinkResolveRateLimiter
import com.example.app.services.LinkResolveService
import com.example.app.services.LinkTokenHasher
import com.example.app.services.OrderDedupStore
import com.example.app.services.OrderDedupStoreRedisson
import com.example.app.services.OrderCheckoutService
import com.example.app.services.PriceDropNotifierImpl
import com.example.app.services.RestockAlertService
import com.example.app.services.RestockNotifierImpl
import com.example.app.services.StorefrontService
import com.example.app.security.TelegramInitDataVerifier
import com.example.bots.TelegramClients
import com.example.domain.watchlist.PriceDropNotifier
import com.example.domain.watchlist.RestockNotifier
import com.example.redis.RedisClientFactory
import io.micrometer.core.instrument.MeterRegistry
import org.koin.dsl.module
import org.redisson.api.RedissonClient

fun appModule(config: AppConfig, meterRegistry: MeterRegistry?) = module {
    single<AppConfig> { config }

    meterRegistry?.let { registry ->
        single<MeterRegistry> { registry }
    }

    single { TelegramClients(config.telegram.adminToken, config.telegram.shopToken, meterRegistry) }

    single<RedissonClient> { RedisClientFactory.create(config.redis.url) }

    single { LinkTokenHasher(config.linkContext.tokenSecret) }
    single { TelegramInitDataVerifier(config.telegram.shopToken, config.telegramInitData.maxAgeSeconds) }
    single { LinkContextService(get(), get()) }
    single { LinkResolveService(get(), get(), get()) }
    single { LinkResolveRateLimiter(get(), get(), config.linkResolveRateLimit) }
    single { StorefrontService(get(), get(), get()) }
    single<CartRedisStore> { CartRedisStoreRedisson(get()) }
    single<OrderDedupStore> { OrderDedupStoreRedisson(get()) }
    single { CartService(config, get(), get(), get(), get(), get(), get(), get(), get()) }
    single { OrderCheckoutService(config, get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    single<PriceDropNotifier> { PriceDropNotifierImpl(config, get(), get()) }
    single<RestockNotifier> { RestockNotifierImpl(config, get(), get(), get()) }
    single { RestockAlertService(get(), get(), config.server.watchlistRestockEnabled) }
    single { InventoryService(get(), get()) }
}

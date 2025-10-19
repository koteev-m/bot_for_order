package com.example.app.di

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.redis.RedisClientFactory
import org.koin.dsl.module
import org.redisson.api.RedissonClient

fun appModule(config: AppConfig) = module {
    single<AppConfig> { config }

    single { TelegramClients(config.telegram.adminToken, config.telegram.shopToken) }

    single<RedissonClient> { RedisClientFactory.create(config.redis.url) }
}

package com.example.app.di

import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldService
import com.example.redis.HoldServiceRedis
import com.example.redis.LockManagerRedis
import com.example.redis.OrderHoldServiceRedis
import org.koin.dsl.module
import org.redisson.api.RedissonClient

val redisBindingsModule = module {
    single<HoldService> { HoldServiceRedis(get<RedissonClient>()) }
    single<LockManager> { LockManagerRedis(get<RedissonClient>()) }
    single<OrderHoldService> { OrderHoldServiceRedis(get<RedissonClient>()) }
}

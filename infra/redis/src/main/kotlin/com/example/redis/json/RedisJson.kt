package com.example.redis.json

import kotlinx.serialization.json.Json

object RedisJson {
    val instance: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

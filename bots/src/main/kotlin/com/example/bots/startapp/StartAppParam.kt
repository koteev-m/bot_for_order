package com.example.bots.startapp

/**
 * Параметры для Mini App через Direct Link (?startapp=...).
 * Все поля безопасно сериализуются в короткую строку, затем кодируются base64url (без '=').
 */
data class StartAppParam(
    val itemId: String,
    val variantId: String? = null,
    val ref: String? = null
)

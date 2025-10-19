package com.example.domain

import kotlinx.serialization.Serializable

@Serializable
data class ItemId(val value: String)

@Serializable
data class Item(
    val id: ItemId,
    val title: String,
    val description: String,
    val active: Boolean
)

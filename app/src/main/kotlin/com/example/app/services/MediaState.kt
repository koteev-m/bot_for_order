package com.example.app.services

import java.util.concurrent.ConcurrentHashMap

enum class MediaType { PHOTO, VIDEO }

data class PendingMedia(
    val fileId: String,
    val type: MediaType
)

data class CollectState(
    val adminId: Long,
    val chatId: Long,
    val itemId: String,
    val media: MutableList<PendingMedia> = mutableListOf()
)

class MediaStateStore {
    private val states = ConcurrentHashMap<Long, CollectState>()

    fun start(adminId: Long, chatId: Long, itemId: String) {
        states[adminId] = CollectState(adminId, chatId, itemId, mutableListOf())
    }

    fun get(adminId: Long): CollectState? = states[adminId]

    fun add(adminId: Long, media: PendingMedia): CollectState? {
        val state = states[adminId] ?: return null
        state.media.add(media)
        return state
    }

    fun clear(adminId: Long) {
        states.remove(adminId)
    }
}

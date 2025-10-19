package com.example.app.tg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val date: Long,
    val text: String? = null,
    val from: TgUser? = null,
    val chat: TgChat,
    @SerialName("media_group_id") val mediaGroupId: String? = null,
    val photo: List<TgPhotoSize>? = null,
    val video: TgVideo? = null
)

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null
)

@Serializable
data class TgPhotoSize(
    @SerialName("file_id") val fileId: String,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class TgVideo(
    @SerialName("file_id") val fileId: String,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null
)

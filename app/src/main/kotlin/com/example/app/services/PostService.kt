package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.bots.startapp.DirectLink
import com.example.bots.startapp.StartAppParam
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.PostsRepository
import com.example.domain.ItemStatus
import com.example.domain.Post
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.InputMedia
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.model.request.InputMediaVideo
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.EditMessageCaption
import com.pengrad.telegrambot.request.GetMe
import com.pengrad.telegrambot.request.SendMediaGroup
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class PostService(
    private val config: AppConfig,
    private val clients: TelegramClients,
    private val itemsRepository: ItemsRepository,
    private val itemMediaRepository: ItemMediaRepository,
    private val postsRepository: PostsRepository
) {
    private val log = LoggerFactory.getLogger(PostService::class.java)
    private val cachedShopUsername = AtomicReference<String?>()

    private suspend fun resolveShopBotUsername(): String {
        cachedShopUsername.get()?.let { return it }
        val response = clients.shopBot.execute(GetMe())
        val username = response.user()?.username()
            ?: error("Shop bot username is null (set BotFather username)")
        cachedShopUsername.set(username)
        return username
    }

    /**
     * Публикует альбом в канал и добавляет CTA под первой карточкой.
     * Возвращает список message_id альбома.
     */
    @Suppress("SpreadOperator")
    suspend fun postItemAlbumToChannel(itemId: String): List<Int> {
        val item = itemsRepository.getById(itemId)
            ?: error("Item not found: $itemId")
        require(item.status != ItemStatus.sold) { "Item is sold" }

        val media = itemMediaRepository.listByItem(itemId)
        require(media.size in 2..10) { "Item $itemId must have 2..10 media (have ${media.size})" }

        val inputMedia: Array<InputMedia<*>> = media.mapIndexed { index, m ->
            val mediaInput: InputMedia<*> = when (m.mediaType) {
                "photo" -> InputMediaPhoto(m.fileId)
                "video" -> InputMediaVideo(m.fileId)
                else -> InputMediaPhoto(m.fileId)
            }
            if (index == 0) {
                mediaInput.caption(formatCaption(item.title, item.description))
                mediaInput.parseMode(ParseMode.HTML)
            }
            mediaInput
        }.toTypedArray()

        val channelId = config.telegram.channelId
        val sendRequest = SendMediaGroup(channelId, *inputMedia)
        val sendResponse = clients.adminBot.execute(sendRequest)
        if (!sendResponse.isOk) {
            error("sendMediaGroup failed: ${sendResponse.description()}")
        }
        val messages = sendResponse.messages() ?: error("sendMediaGroup failed: no messages")
        val messageIds = messages.map { it.messageId() }

        val shopUsername = resolveShopBotUsername()
        val link = DirectLink.forMiniApp(shopUsername, null, StartAppParam(itemId = itemId))
        val keyboard = InlineKeyboardMarkup(
            InlineKeyboardButton("Купить").url(link)
        )

        val firstMessageId = messageIds.first()
        val newCaption = formatCaption(item.title, item.description)
        val editRequest = EditMessageCaption(channelId, firstMessageId)
            .caption(newCaption)
            .parseMode(ParseMode.HTML)
            .replyMarkup(keyboard)

        val editResponse = clients.adminBot.execute(editRequest)
        if (!editResponse.isOk) {
            log.warn(
                "EditMessageCaption failed: {} {}",
                editResponse.errorCode(),
                editResponse.description()
            )
        }

        postsRepository.insert(
            Post(
                id = 0,
                itemId = itemId,
                channelMsgIds = messageIds
            )
        )

        return messageIds
    }

    private fun formatCaption(title: String, description: String): String = buildString {
        append("<b>").append(escapeHtml(title)).append("</b>\n")
        append(escapeHtml(description))
    }

    private fun escapeHtml(source: String): String = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

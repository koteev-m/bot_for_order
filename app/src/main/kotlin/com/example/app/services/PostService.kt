package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.bots.startapp.DirectLink
import com.example.bots.startapp.MiniAppMode
import com.example.db.ChannelBindingsRepository
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.PostsRepository
import com.example.db.StorefrontsRepository
import com.example.domain.ItemStatus
import com.example.domain.ItemMedia
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.Post
import com.example.domain.Storefront
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.InputMedia
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.model.request.InputMediaVideo
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.request.GetMe
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.request.SendVideo
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class PostService(
    private val config: AppConfig,
    private val clients: TelegramClients,
    private val itemsRepository: ItemsRepository,
    private val itemMediaRepository: ItemMediaRepository,
    private val postsRepository: PostsRepository,
    private val linkContextService: LinkContextService,
    private val channelBindingsRepository: ChannelBindingsRepository,
    private val storefrontsRepository: StorefrontsRepository
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
     * Публикует медиа в канал и добавляет CTA под первым сообщением.
     * Возвращает список message_id сообщений.
     */
    @Suppress("SpreadOperator")
    suspend fun postItemAlbumToChannel(itemId: String): List<Int> {
        val item = itemsRepository.getById(itemId)
            ?: error("Item not found: $itemId")
        require(item.status != ItemStatus.sold) { "Item is sold" }

        val media = itemMediaRepository.listByItem(itemId)
        val channelId = config.telegram.channelId
        val messageIds = when (media.size) {
            in 2..10 -> sendMediaGroup(channelId, item.title, item.description, media)
            1 -> sendSingleMedia(channelId, item.title, item.description, media.first())
            else -> error("Item $itemId must have 1..10 media (have ${media.size})")
        }

        val shopUsername = resolveShopBotUsername()
        val firstMessageId = messageIds.first()
        val storefrontId = resolveStorefrontId(channelId, item.merchantId)
        val addToken = linkContextService.create(
            LinkContextCreateRequest(
                merchantId = item.merchantId,
                storefrontId = storefrontId,
                channelId = channelId,
                postMessageId = firstMessageId,
                listingId = item.id,
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                expiresAt = null,
                metadataJson = "{}"
            )
        ).token
        val buyToken = linkContextService.create(
            LinkContextCreateRequest(
                merchantId = item.merchantId,
                storefrontId = storefrontId,
                channelId = channelId,
                postMessageId = firstMessageId,
                listingId = item.id,
                action = LinkAction.BUY,
                button = LinkButton.BUY,
                expiresAt = null,
                metadataJson = "{}"
            )
        ).token
        val appName = config.telegram.buyerMiniAppShortName.takeIf { it.isNotBlank() }
        val addLink = DirectLink.forMiniApp(shopUsername, appName, addToken, MiniAppMode.COMPACT)
        val buyLink = DirectLink.forMiniApp(shopUsername, appName, buyToken, MiniAppMode.DEFAULT)
        val keyboard = InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton("Добавить в корзину").url(addLink),
                InlineKeyboardButton("Оформить").url(buyLink)
            )
        )

        val editRequest = EditMessageReplyMarkup(channelId, firstMessageId)
            .replyMarkup(keyboard)

        val editResponse = clients.adminBot.execute(editRequest)
        if (!editResponse.isOk) {
            runCatching { linkContextService.revoke(addToken) }
            runCatching { linkContextService.revoke(buyToken) }
            val errorCode = editResponse.errorCode()
            val errorDesc = editResponse.description()
            log.warn(
                "EditMessageReplyMarkup failed: {} {} channelId={} messageId={}",
                errorCode,
                errorDesc,
                channelId,
                firstMessageId
            )
            throw IllegalStateException(
                "failed to attach CTA keyboard: code=$errorCode desc=$errorDesc " +
                    "channelId=$channelId messageId=$firstMessageId"
            )
        }

        postsRepository.insert(
            Post(
                id = 0,
                merchantId = item.merchantId,
                itemId = itemId,
                channelMsgIds = messageIds
            )
        )

        return messageIds
    }

    @Suppress("SpreadOperator")
    private suspend fun sendMediaGroup(
        channelId: Long,
        title: String,
        description: String,
        media: List<ItemMedia>
    ): List<Int> {
        val inputMedia: Array<InputMedia<*>> = media.mapIndexed { index, m ->
            val mediaInput: InputMedia<*> = when (m.mediaType) {
                "photo" -> InputMediaPhoto(m.fileId)
                "video" -> InputMediaVideo(m.fileId)
                else -> InputMediaPhoto(m.fileId)
            }
            if (index == 0) {
                mediaInput.caption(formatCaption(title, description))
                mediaInput.parseMode(ParseMode.HTML)
            }
            mediaInput
        }.toTypedArray()

        val sendRequest = SendMediaGroup(channelId, *inputMedia)
        val sendResponse = clients.adminBot.execute(sendRequest)
        if (!sendResponse.isOk) {
            error("sendMediaGroup failed: ${sendResponse.description()}")
        }
        val messages = sendResponse.messages() ?: error("sendMediaGroup failed: no messages")
        return messages.map { it.messageId() }
    }

    private suspend fun sendSingleMedia(
        channelId: Long,
        title: String,
        description: String,
        media: ItemMedia
    ): List<Int> {
        val caption = formatCaption(title, description)
        val response = if (media.mediaType == "video") {
            clients.adminBot.execute(
                SendVideo(channelId, media.fileId)
                    .caption(caption)
                    .parseMode(ParseMode.HTML)
            )
        } else {
            clients.adminBot.execute(
                SendPhoto(channelId, media.fileId)
                    .caption(caption)
                    .parseMode(ParseMode.HTML)
            )
        }
        if (!response.isOk) {
            error("sendMedia failed: ${response.description()}")
        }
        val messageId = response.message()?.messageId()
            ?: error("sendMedia failed: no message")
        return listOf(messageId)
    }

    private suspend fun resolveStorefrontId(channelId: Long, merchantId: String): String {
        val existing = channelBindingsRepository.getByChannel(channelId)
        if (existing != null) {
            return existing.storefrontId
        }
        val storefrontId = DEFAULT_STOREFRONT_ID
        val storefront = storefrontsRepository.getById(storefrontId)
        if (storefront == null) {
            try {
                storefrontsRepository.create(
                    Storefront(
                        id = storefrontId,
                        merchantId = merchantId,
                        name = DEFAULT_STOREFRONT_NAME
                    )
                )
            } catch (e: Exception) {
                if (!isUniqueViolation(e)) {
                    throw e
                }
            }
        }
        try {
            channelBindingsRepository.bind(storefrontId, channelId, Instant.now())
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) {
                throw e
            }
        }
        val created = channelBindingsRepository.getByChannel(channelId)
            ?: error("Channel binding not found after bootstrap for channel $channelId")
        return created.storefrontId
    }

    private fun formatCaption(title: String, description: String): String = buildString {
        append("<b>").append(escapeHtml(title)).append("</b>\n")
        append(escapeHtml(description))
    }

    private fun escapeHtml(source: String): String = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun isUniqueViolation(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .filterIsInstance<SQLException>()
            .any { it.sqlState == "23505" }

    private companion object {
        private const val DEFAULT_STOREFRONT_ID = "default-storefront"
        private const val DEFAULT_STOREFRONT_NAME = "Основная витрина"
    }
}

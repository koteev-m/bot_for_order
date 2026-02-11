package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.bots.startapp.DirectLink
import com.example.bots.startapp.MiniAppMode
import com.example.db.ChannelBindingsRepository
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.OutboxRepository
import com.example.db.PostsRepository
import com.example.db.StorefrontsRepository
import com.example.db.TelegramPublishAlbumStateRepository
import com.example.domain.ItemMedia
import com.example.domain.ItemStatus
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
import com.pengrad.telegrambot.request.PinChatMessage
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.request.SendVideo
import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class TelegramPublishAlbumPayload(
    val itemId: String,
    val channelId: Long,
    val operationId: String? = null
)

@Serializable
data class TelegramEditReplyMarkupPayload(
    val channelId: Long,
    val messageId: Int,
    val addToken: String,
    val buyToken: String,
    val itemId: String
)

@Serializable
data class TelegramPinMessagePayload(
    val channelId: Long,
    val messageId: Int,
    val itemId: String
)

class TelegramOutboxHandlers(
    private val config: AppConfig,
    private val clients: TelegramClients,
    private val itemsRepository: ItemsRepository,
    private val itemMediaRepository: ItemMediaRepository,
    private val postsRepository: PostsRepository,
    private val outboxRepository: OutboxRepository,
    private val linkContextService: LinkContextService,
    private val channelBindingsRepository: ChannelBindingsRepository,
    private val storefrontsRepository: StorefrontsRepository,
    private val publishStateRepository: TelegramPublishAlbumStateRepository,
    meterRegistry: MeterRegistry? = null
) {
    private val log = LoggerFactory.getLogger(TelegramOutboxHandlers::class.java)
    private val outboxJson = Json { ignoreUnknownKeys = true }
    private val cachedShopUsername = AtomicReference<String?>()
    private val enqueueDoneCounter = meterRegistry?.counter("outbox_enqueue_total", "result", "done")
    private val enqueueFailedCounter = meterRegistry?.counter("outbox_enqueue_total", "result", "failed")

    suspend fun publishAlbum(payloadJson: String) {
        val payload = outboxJson.decodeFromString(TelegramPublishAlbumPayload.serializer(), payloadJson)
        val operationId = payload.operationId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: stableLegacyOperationId(payloadJson)
        val item = itemsRepository.getById(payload.itemId)
            ?: error("Item not found: ${payload.itemId}")
        require(item.status != ItemStatus.sold) { "Item is sold" }

        val now = Instant.now()
        publishStateRepository.upsertOperation(operationId, payload.itemId, payload.channelId, now)
        var state = checkNotNull(publishStateRepository.getByOperationId(operationId))

        val media = itemMediaRepository.listByItem(payload.itemId)
        val messageIds = state.messageIdsJson
            ?.let { outboxJson.decodeFromString<List<Int>>(it) }
            ?: run {
                val sentMessageIds = when (media.size) {
                    in 2..10 -> sendMediaGroup(payload.channelId, item.title, item.description, media)
                    1 -> sendSingleMedia(payload.channelId, item.title, item.description, media.first())
                    else -> error("Item ${payload.itemId} must have 1..10 media (have ${media.size})")
                }
                publishStateRepository.saveMessages(
                    operationId = operationId,
                    messageIdsJson = outboxJson.encodeToString(sentMessageIds),
                    firstMessageId = sentMessageIds.first(),
                    now = now
                )
                sentMessageIds
            }

        if (!state.postInserted && !isPostAlreadyStored(payload.itemId, messageIds)) {
            postsRepository.insert(
                Post(id = 0, merchantId = item.merchantId, itemId = payload.itemId, channelMsgIds = messageIds)
            )
            publishStateRepository.markPostInserted(operationId, Instant.now())
        } else if (!state.postInserted) {
            publishStateRepository.markPostInserted(operationId, Instant.now())
        }

        state = checkNotNull(publishStateRepository.getByOperationId(operationId))

        val firstMessageId = messageIds.first()
        val storefrontId = resolveStorefrontId(payload.channelId, item.merchantId)

        val addToken = state.addToken ?: linkContextService.create(
            LinkContextCreateRequest(
                merchantId = item.merchantId,
                storefrontId = storefrontId,
                channelId = payload.channelId,
                postMessageId = firstMessageId,
                listingId = item.id,
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                expiresAt = null,
                metadataJson = "{}"
            )
        ).token.also { token -> publishStateRepository.saveAddToken(operationId, token, Instant.now()) }

        val buyToken = state.buyToken ?: linkContextService.create(
            LinkContextCreateRequest(
                merchantId = item.merchantId,
                storefrontId = storefrontId,
                channelId = payload.channelId,
                postMessageId = firstMessageId,
                listingId = item.id,
                action = LinkAction.BUY,
                button = LinkButton.BUY,
                expiresAt = null,
                metadataJson = "{}"
            )
        ).token.also { token -> publishStateRepository.saveBuyToken(operationId, token, Instant.now()) }

        state = checkNotNull(publishStateRepository.getByOperationId(operationId))

        if (!state.editEnqueued) {
            enqueueEdit(
                type = TELEGRAM_EDIT_REPLY_MARKUP,
                payload = TelegramEditReplyMarkupPayload(
                    channelId = payload.channelId,
                    messageId = firstMessageId,
                    addToken = addToken,
                    buyToken = buyToken,
                    itemId = payload.itemId
                )
            )
            publishStateRepository.markEditEnqueued(operationId, Instant.now())
        }

        if (!state.pinEnqueued) {
            enqueuePin(
                type = TELEGRAM_PIN_MESSAGE,
                payload = TelegramPinMessagePayload(
                    channelId = payload.channelId,
                    messageId = firstMessageId,
                    itemId = payload.itemId
                )
            )
            publishStateRepository.markPinEnqueued(operationId, Instant.now())
        }
    }

    private fun stableLegacyOperationId(payloadJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payloadJson.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private suspend fun isPostAlreadyStored(itemId: String, messageIds: List<Int>): Boolean {
        return postsRepository.listByItem(itemId).any { post ->
            post.channelMsgIds == messageIds
        }
    }

    suspend fun editReplyMarkup(payloadJson: String) {
        val payload = outboxJson.decodeFromString(TelegramEditReplyMarkupPayload.serializer(), payloadJson)
        val shopUsername = resolveShopBotUsername()
        val appName = config.telegram.buyerMiniAppShortName.takeIf { it.isNotBlank() }
        val addLink = DirectLink.forMiniApp(shopUsername, appName, payload.addToken, MiniAppMode.COMPACT)
        val buyLink = DirectLink.forMiniApp(shopUsername, appName, payload.buyToken, MiniAppMode.DEFAULT)
        val keyboard = InlineKeyboardMarkup(
            arrayOf(
                InlineKeyboardButton("Добавить в корзину").url(addLink),
                InlineKeyboardButton("Оформить").url(buyLink)
            )
        )

        val response = clients.adminBot.execute(
            EditMessageReplyMarkup(payload.channelId, payload.messageId).replyMarkup(keyboard)
        )
        if (!response.isOk && !isMessageNotModified(response.description())) {
            error("failed to attach CTA keyboard: code=${response.errorCode()} desc=${response.description()}")
        }
    }

    suspend fun pinMessage(payloadJson: String) {
        val payload = outboxJson.decodeFromString(TelegramPinMessagePayload.serializer(), payloadJson)
        val response = clients.adminBot.execute(PinChatMessage(payload.channelId, payload.messageId).disableNotification(true))
        if (!response.isOk && !isAlreadyPinned(response.description())) {
            error("failed to pin message: code=${response.errorCode()} desc=${response.description()}")
        }
    }

    private suspend fun enqueueEdit(type: String, payload: TelegramEditReplyMarkupPayload) {
        val payloadJson = outboxJson.encodeToString(TelegramEditReplyMarkupPayload.serializer(), payload)
        enqueue(type, payloadJson)
    }

    private suspend fun enqueuePin(type: String, payload: TelegramPinMessagePayload) {
        val payloadJson = outboxJson.encodeToString(TelegramPinMessagePayload.serializer(), payload)
        enqueue(type, payloadJson)
    }

    private suspend fun enqueue(type: String, payloadJson: String) {
        runCatching {
            outboxRepository.insert(type, payloadJson, Instant.now())
        }.onSuccess {
            enqueueDoneCounter?.increment()
            log.info("outbox_enqueue_done type={}", type)
        }.onFailure { error ->
            enqueueFailedCounter?.increment()
            log.error("outbox_enqueue_failed type={} reason={}", type, error.message, error)
            throw error
        }
    }

    private suspend fun sendMediaGroup(channelId: Long, title: String, description: String, media: List<ItemMedia>): List<Int> {
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

        val sendResponse = clients.adminBot.execute(SendMediaGroup(channelId, *inputMedia))
        if (!sendResponse.isOk) {
            error("sendMediaGroup failed: ${sendResponse.description()}")
        }
        val messages = sendResponse.messages() ?: error("sendMediaGroup failed: no messages")
        return messages.map { it.messageId() }
    }

    private fun sendSingleMedia(channelId: Long, title: String, description: String, media: ItemMedia): List<Int> {
        val caption = formatCaption(title, description)
        val response = if (media.mediaType == "video") {
            clients.adminBot.execute(SendVideo(channelId, media.fileId).caption(caption).parseMode(ParseMode.HTML))
        } else {
            clients.adminBot.execute(SendPhoto(channelId, media.fileId).caption(caption).parseMode(ParseMode.HTML))
        }
        if (!response.isOk) {
            error("sendMedia failed: ${response.description()}")
        }
        val messageId = response.message()?.messageId() ?: error("sendMedia failed: no message")
        return listOf(messageId)
    }

    private suspend fun resolveShopBotUsername(): String {
        cachedShopUsername.get()?.let { return it }
        val response = clients.shopBot.execute(GetMe())
        val username = response.user()?.username() ?: error("Shop bot username is null (set BotFather username)")
        cachedShopUsername.set(username)
        return username
    }

    private suspend fun resolveStorefrontId(channelId: Long, merchantId: String): String {
        val existing = channelBindingsRepository.getByChannel(channelId)
        if (existing != null) return existing.storefrontId
        val storefrontId = DEFAULT_STOREFRONT_ID
        val storefront = storefrontsRepository.getById(storefrontId)
        if (storefront == null) {
            try {
                storefrontsRepository.create(Storefront(id = storefrontId, merchantId = merchantId, name = DEFAULT_STOREFRONT_NAME))
            } catch (e: Exception) {
                if (!isUniqueViolation(e)) throw e
            }
        }
        try {
            channelBindingsRepository.bind(storefrontId, channelId, Instant.now())
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
        }
        return channelBindingsRepository.getByChannel(channelId)?.storefrontId
            ?: error("Channel binding not found after bootstrap for channel $channelId")
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

    private fun isAlreadyPinned(description: String?): Boolean =
        description?.contains("already pinned", ignoreCase = true) == true

    private fun isMessageNotModified(description: String?): Boolean =
        description?.contains("message is not modified", ignoreCase = true) == true

    companion object {
        const val TELEGRAM_PUBLISH_ALBUM = "telegram_publish_album"
        const val TELEGRAM_PIN_MESSAGE = "telegram_pin_message"
        const val TELEGRAM_EDIT_REPLY_MARKUP = "telegram_edit_reply_markup"
        private const val DEFAULT_STOREFRONT_ID = "default-storefront"
        private const val DEFAULT_STOREFRONT_NAME = "Основная витрина"
    }
}

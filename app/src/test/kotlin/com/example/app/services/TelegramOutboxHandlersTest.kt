package com.example.app.services

import com.example.app.baseTestConfig
import com.example.bots.InstrumentedTelegramBot
import com.example.bots.TelegramClients
import com.example.db.ChannelBindingsRepository
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.OutboxRepository
import com.example.db.PostsRepository
import com.example.db.StorefrontsRepository
import com.example.db.TelegramPublishAlbumState
import com.example.db.TelegramPublishAlbumStateRepository
import com.example.domain.ChannelBinding
import com.example.domain.Item
import com.example.domain.ItemMedia
import com.example.domain.ItemStatus
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import com.example.domain.Post
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.response.MessagesResponse
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class TelegramOutboxHandlersTest {

    @Test
    fun `publish album retry does not send album twice`() = runBlocking {
        val itemId = "item-1"
        val channelId = -100123L
        val operationId = "op-fixed"

        val itemsRepository = mockk<ItemsRepository>()
        val mediaRepository = mockk<ItemMediaRepository>()
        val postsRepository = mockk<PostsRepository>()
        val outboxRepository = mockk<OutboxRepository>()
        val linkContextService = mockk<LinkContextService>()
        val channelBindingsRepository = mockk<ChannelBindingsRepository>()
        val storefrontsRepository = mockk<StorefrontsRepository>(relaxed = true)
        val stateRepository = InMemoryTelegramPublishAlbumStateRepository()

        val adminBot = mockk<InstrumentedTelegramBot>()
        val shopBot = mockk<InstrumentedTelegramBot>(relaxed = true)
        val clients = mockk<TelegramClients>()
        every { clients.adminBot } returns adminBot
        every { clients.shopBot } returns shopBot

        coEvery { itemsRepository.getById(itemId) } returns Item(
            id = itemId,
            merchantId = "m-1",
            title = "title",
            description = "desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        coEvery { mediaRepository.listByItem(itemId) } returns listOf(
            ItemMedia(1L, itemId, "f-1", "photo", 0),
            ItemMedia(2L, itemId, "f-2", "photo", 1)
        )
        val insertedPosts = mutableListOf<Post>()
        coEvery { postsRepository.insert(any()) } coAnswers {
            insertedPosts += firstArg<Post>()
            1L
        }
        coEvery { postsRepository.listByItem(itemId) } answers { insertedPosts.toList() }

        coEvery { channelBindingsRepository.getByChannel(channelId) } returns ChannelBinding(
            id = 1L,
            storefrontId = "sf-1",
            channelId = channelId,
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        coEvery {
            linkContextService.create(match { it.action == LinkAction.ADD })
        } returns LinkContextCreateResult(
            token = "add-token",
            context = LinkContext(
                id = 1,
                tokenHash = "h1",
                merchantId = "m-1",
                storefrontId = "sf-1",
                channelId = channelId,
                postMessageId = 10,
                listingId = itemId,
                action = LinkAction.ADD,
                button = LinkButton.ADD,
                createdAt = Instant.now(),
                revokedAt = null,
                expiresAt = null,
                metadataJson = "{}"
            )
        )
        coEvery {
            linkContextService.create(match { it.action == LinkAction.BUY })
        } returns LinkContextCreateResult(
            token = "buy-token",
            context = LinkContext(
                id = 2,
                tokenHash = "h2",
                merchantId = "m-1",
                storefrontId = "sf-1",
                channelId = channelId,
                postMessageId = 10,
                listingId = itemId,
                action = LinkAction.BUY,
                button = LinkButton.BUY,
                createdAt = Instant.now(),
                revokedAt = null,
                expiresAt = null,
                metadataJson = "{}"
            )
        )

        val sendResponse = mockk<MessagesResponse>()
        every { sendResponse.isOk } returns true
        every { sendResponse.messages() } returns arrayOf(
            mockk<Message>(relaxed = true) { every { messageId() } returns 10 },
            mockk<Message>(relaxed = true) { every { messageId() } returns 11 }
        )
        every { adminBot.execute(any<SendMediaGroup>()) } returns sendResponse

        var failEditOnce = true
        coEvery { outboxRepository.insert(any(), any(), any()) } coAnswers {
            val type = firstArg<String>()
            if (type == TelegramOutboxHandlers.TELEGRAM_EDIT_REPLY_MARKUP && failEditOnce) {
                failEditOnce = false
                throw IllegalStateException("enqueue failed")
            }
            1L
        }

        val handlers = TelegramOutboxHandlers(
            config = baseTestConfig(),
            clients = clients,
            itemsRepository = itemsRepository,
            itemMediaRepository = mediaRepository,
            postsRepository = postsRepository,
            outboxRepository = outboxRepository,
            linkContextService = linkContextService,
            channelBindingsRepository = channelBindingsRepository,
            storefrontsRepository = storefrontsRepository,
            publishStateRepository = stateRepository,
            meterRegistry = null
        )

        val payloadJson = Json.encodeToString(
            TelegramPublishAlbumPayload.serializer(),
            TelegramPublishAlbumPayload(itemId = itemId, channelId = channelId, operationId = operationId)
        )

        runCatching { handlers.publishAlbum(payloadJson) }.exceptionOrNull()?.message shouldBe "enqueue failed"
        handlers.publishAlbum(payloadJson)

        verify(exactly = 1) { adminBot.execute(any<SendMediaGroup>()) }
        coVerify(exactly = 1) { postsRepository.insert(any()) }
        coVerify(exactly = 2) { outboxRepository.insert(TelegramOutboxHandlers.TELEGRAM_EDIT_REPLY_MARKUP, any(), any()) }
        coVerify(exactly = 1) { outboxRepository.insert(TelegramOutboxHandlers.TELEGRAM_PIN_MESSAGE, any(), any()) }
        coVerify(exactly = 1) { linkContextService.create(match { it.action == LinkAction.ADD }) }
        coVerify(exactly = 1) { linkContextService.create(match { it.action == LinkAction.BUY }) }
    }
}

private class InMemoryTelegramPublishAlbumStateRepository : TelegramPublishAlbumStateRepository {
    private val states = mutableMapOf<String, TelegramPublishAlbumState>()

    override suspend fun upsertOperation(operationId: String, itemId: String, channelId: Long, now: Instant) {
        val existing = states[operationId]
        states[operationId] = if (existing == null) {
            TelegramPublishAlbumState(
                operationId = operationId,
                itemId = itemId,
                channelId = channelId,
                messageIdsJson = null,
                firstMessageId = null,
                addToken = null,
                buyToken = null,
                postInserted = false,
                editEnqueued = false,
                pinEnqueued = false
            )
        } else {
            existing.copy(itemId = itemId, channelId = channelId)
        }
    }

    override suspend fun getByOperationId(operationId: String): TelegramPublishAlbumState? = states[operationId]

    override suspend fun saveMessages(operationId: String, messageIdsJson: String, firstMessageId: Int, now: Instant) {
        states.computeIfPresent(operationId) { _, state ->
            state.copy(messageIdsJson = messageIdsJson, firstMessageId = firstMessageId)
        }
    }

    override suspend fun saveAddToken(operationId: String, addToken: String, now: Instant) {
        states.computeIfPresent(operationId) { _, state -> state.copy(addToken = addToken) }
    }

    override suspend fun saveBuyToken(operationId: String, buyToken: String, now: Instant) {
        states.computeIfPresent(operationId) { _, state -> state.copy(buyToken = buyToken) }
    }

    override suspend fun markPostInserted(operationId: String, now: Instant) {
        states.computeIfPresent(operationId) { _, state -> state.copy(postInserted = true) }
    }

    override suspend fun markEditEnqueued(operationId: String, now: Instant) {
        states.computeIfPresent(operationId) { _, state -> state.copy(editEnqueued = true) }
    }

    override suspend fun markPinEnqueued(operationId: String, now: Instant) {
        states.computeIfPresent(operationId) { _, state -> state.copy(pinEnqueued = true) }
    }
}

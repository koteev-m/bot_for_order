package com.example.app.services

import com.example.app.baseTestConfig
import com.example.db.ChannelBindingsRepository
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.OutboxRepository
import com.example.db.PostsRepository
import com.example.db.StorefrontsRepository
import com.example.domain.BargainRules
import com.example.domain.Item
import com.example.domain.ItemMedia
import com.example.domain.ItemStatus
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class PostServiceOutboxTest {

    @Test
    fun `post enqueue uses telegram_publish_album payload with required fields`(): Unit = runBlocking {
        val itemsRepository = mockk<ItemsRepository>()
        val itemMediaRepository = mockk<ItemMediaRepository>()
        val postsRepository = mockk<PostsRepository>(relaxed = true)
        val outboxRepository = mockk<OutboxRepository>()
        val linkContextService = mockk<LinkContextService>(relaxed = true)
        val channelBindingsRepository = mockk<ChannelBindingsRepository>(relaxed = true)
        val storefrontsRepository = mockk<StorefrontsRepository>(relaxed = true)
        val capturedPayloads = mutableListOf<String>()

        coEvery { itemsRepository.getById("item-1") } returns Item(
            id = "item-1",
            merchantId = "m-1",
            title = "title",
            description = "desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = BargainRules(70, 60, 2, 60, 3600, 5)
        )
        coEvery { itemMediaRepository.listByItem("item-1") } returns listOf(
            ItemMedia(1, "item-1", "file-1", "photo", 0)
        )
        coEvery { outboxRepository.insert(any(), capture(capturedPayloads), any()) } returns 1L

        val service = PostService(
            config = baseTestConfig(),
            itemsRepository = itemsRepository,
            itemMediaRepository = itemMediaRepository,
            postsRepository = postsRepository,
            outboxRepository = outboxRepository,
            linkContextService = linkContextService,
            channelBindingsRepository = channelBindingsRepository,
            storefrontsRepository = storefrontsRepository,
            meterRegistry = null
        )

        val outboxId = service.postItemAlbumToChannel("item-1")

        coVerify(exactly = 1) { outboxRepository.insert(PostService.TELEGRAM_PUBLISH_ALBUM, any(), any()) }
        val payload = Json.decodeFromString(TelegramPublishAlbumPayload.serializer(), capturedPayloads.single())
        payload.itemId shouldBe "item-1"
        payload.channelId shouldBe baseTestConfig().telegram.channelId
        payload.operationId.isNotBlank() shouldBe true
        outboxId shouldBe 1L
    }

    @Test
    fun `enqueue failure bubbles up`(): Unit = runBlocking {
        val itemsRepository = mockk<ItemsRepository>()
        val itemMediaRepository = mockk<ItemMediaRepository>()
        val outboxRepository = mockk<OutboxRepository>()

        coEvery { itemsRepository.getById("item-1") } returns Item(
            id = "item-1",
            merchantId = "m-1",
            title = "title",
            description = "desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        coEvery { itemMediaRepository.listByItem("item-1") } returns listOf(
            ItemMedia(1, "item-1", "file-1", "photo", 0)
        )
        coEvery { outboxRepository.insert(any(), any(), any<Instant>()) } throws IllegalStateException("db down")

        val service = PostService(
            config = baseTestConfig(),
            itemsRepository = itemsRepository,
            itemMediaRepository = itemMediaRepository,
            postsRepository = mockk(relaxed = true),
            outboxRepository = outboxRepository,
            linkContextService = mockk(relaxed = true),
            channelBindingsRepository = mockk(relaxed = true),
            storefrontsRepository = mockk(relaxed = true),
            meterRegistry = null
        )

        kotlin.runCatching { service.postItemAlbumToChannel("item-1") }
            .exceptionOrNull()
            ?.message shouldBe "db down"
    }
}

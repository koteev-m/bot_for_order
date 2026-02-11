package com.example.app.services

import com.example.app.config.AppConfig
import com.example.db.ChannelBindingsRepository
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.OutboxRepository
import com.example.db.PostsRepository
import com.example.db.StorefrontsRepository
import com.example.domain.ItemStatus
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class PostService(
    private val config: AppConfig,
    private val itemsRepository: ItemsRepository,
    private val itemMediaRepository: ItemMediaRepository,
    @Suppress("unused") private val postsRepository: PostsRepository,
    private val outboxRepository: OutboxRepository,
    @Suppress("unused") private val linkContextService: LinkContextService,
    @Suppress("unused") private val channelBindingsRepository: ChannelBindingsRepository,
    @Suppress("unused") private val storefrontsRepository: StorefrontsRepository,
    meterRegistry: MeterRegistry? = null
) {
    private val log = LoggerFactory.getLogger(PostService::class.java)
    private val outboxJson = Json { ignoreUnknownKeys = true }
    private val enqueueDoneCounter = meterRegistry?.counter(
        "outbox_enqueue_total",
        "type",
        TELEGRAM_PUBLISH_ALBUM,
        "result",
        "done"
    )
    private val enqueueFailedCounter = meterRegistry?.counter(
        "outbox_enqueue_total",
        "type",
        TELEGRAM_PUBLISH_ALBUM,
        "result",
        "failed"
    )

    data class PublishResult(
        val channelId: Long,
        val ok: Boolean,
        val error: String? = null
    )

    suspend fun postItemAlbumToChannel(itemId: String): Long {
        val channelId = config.telegram.channelId
        return postItemAlbumToChannel(itemId, channelId)
    }

    suspend fun postItemAlbumToChannels(itemId: String, channelIds: List<Long>): List<PublishResult> {
        if (channelIds.isEmpty()) return emptyList()
        return channelIds.distinct().map { channelId ->
            runCatching {
                postItemAlbumToChannel(itemId, channelId)
                PublishResult(channelId = channelId, ok = true)
            }.getOrElse { error ->
                log.warn(
                    "publish enqueue failed itemId={} channelId={} error={}",
                    itemId,
                    channelId,
                    error.message
                )
                PublishResult(channelId = channelId, ok = false, error = error.message ?: "publish_enqueue_failed")
            }
        }
    }

    private suspend fun postItemAlbumToChannel(itemId: String, channelId: Long): Long {
        val item = itemsRepository.getById(itemId)
            ?: error("Item not found: $itemId")
        require(item.status != ItemStatus.sold) { "Item is sold" }

        val media = itemMediaRepository.listByItem(itemId)
        require(media.size in 1..10) { "Item $itemId must have 1..10 media (have ${media.size})" }

        val payload = TelegramPublishAlbumPayload(itemId = itemId, channelId = channelId, operationId = UUID.randomUUID().toString())
        val payloadJson = outboxJson.encodeToString(TelegramPublishAlbumPayload.serializer(), payload)
        return runCatching {
            outboxRepository.insert(TELEGRAM_PUBLISH_ALBUM, payloadJson, Instant.now())
        }.onSuccess {
            enqueueDoneCounter?.increment()
            log.info("outbox_enqueue_done type={} itemId={} channelId={}", TELEGRAM_PUBLISH_ALBUM, itemId, channelId)
        }.onFailure { error ->
            enqueueFailedCounter?.increment()
            log.error(
                "outbox_enqueue_failed type={} itemId={} channelId={} reason={}",
                TELEGRAM_PUBLISH_ALBUM,
                itemId,
                channelId,
                error.message,
                error
            )
            throw error
        }.getOrThrow()
    }

    companion object {
        const val TELEGRAM_PUBLISH_ALBUM = "telegram_publish_album"
    }
}

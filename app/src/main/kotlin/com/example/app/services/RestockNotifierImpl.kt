package com.example.app.services

import com.example.app.config.AppConfig
import com.example.app.routes.disablePreview
import com.example.bots.TelegramClients
import com.example.domain.watchlist.RestockNotifier
import com.example.domain.watchlist.WatchlistRepository
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.SendMessage
import java.util.concurrent.TimeUnit
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory

class RestockNotifierImpl(
    private val cfg: AppConfig,
    private val clients: TelegramClients,
    private val watchlistRepository: WatchlistRepository,
    private val redisson: RedissonClient
) : RestockNotifier {

    private val log = LoggerFactory.getLogger(RestockNotifierImpl::class.java)
    private val cooldownSec = cfg.server.restockNotifyCooldownSec.toLong().coerceAtLeast(1L)

    override suspend fun notify(userId: Long, itemId: String, variantId: String?) {
        val variantKey = variantId ?: VARIANT_WILDCARD
        val bucket = redisson.getBucket<String>("$REDIS_PREFIX:$userId:$itemId:$variantKey")
        if (!bucket.trySet("1", cooldownSec, TimeUnit.SECONDS)) {
            return
        }
        val url = buildUrl(itemId, variantId)
        val text = if (variantId == null) ITEM_TEXT else VARIANT_TEXT
        val markup = InlineKeyboardMarkup(InlineKeyboardButton("Открыть").url(url))
        val sendResult = runCatching {
            clients.shopBot.execute(
                SendMessage(userId, text)
                    .disablePreview()
                    .replyMarkup(markup)
            )
        }
        sendResult.onSuccess {
            log.info("restock_alert_sent item={} variant={}", itemId, variantKey)
            if (cfg.server.restockNotifyConsume) {
                watchlistRepository.deleteRestock(userId, itemId, variantId)
            }
        }.onFailure { error ->
            bucket.delete()
            log.warn(
                "restock_notify_error item={} variant={} cause={}",
                itemId,
                variantKey,
                error.message
            )
        }
    }

    private fun buildUrl(itemId: String, variantId: String?): String {
        val base = cfg.server.publicBaseUrl.trimEnd('/')
        val variantQuery = variantId?.let { "&variant=$it" } ?: ""
        return "$base/app/?item=$itemId$variantQuery"
    }

    private companion object {
        private const val REDIS_PREFIX = "notify:restock"
        private const val VARIANT_WILDCARD = "_"
        private const val ITEM_TEXT = "🔔 Товар снова в наличии."
        private const val VARIANT_TEXT = "🔔 Вариант снова в наличии."
    }
}

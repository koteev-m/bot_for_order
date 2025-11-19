package com.example.app.services

import com.example.app.config.AppConfig
import com.example.app.routes.disablePreview
import com.example.bots.TelegramClients
import com.example.domain.watchlist.PriceDropNotifier
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory

class PriceDropNotifierImpl(
    private val cfg: AppConfig,
    private val clients: TelegramClients,
    private val redisson: RedissonClient
) : PriceDropNotifier {

    private val log = LoggerFactory.getLogger(PriceDropNotifierImpl::class.java)
    private val cooldownSec: Long = cfg.server.priceDropNotifyCooldownSec.toLong().coerceAtLeast(1L)

    override suspend fun notify(userId: Long, itemId: String, currentPriceMinor: Long) {
        val key = redisKey(userId, itemId)
        val bucket = redisson.getBucket<String>(key)
        if (!bucket.trySet("1", cooldownSec, TimeUnit.SECONDS)) {
            return
        }
        val currency = cfg.payments.invoiceCurrency.uppercase()
        val text = buildMessage(currentPriceMinor, currency)
        val url = buildItemUrl(itemId)
        val markup = InlineKeyboardMarkup(InlineKeyboardButton("Открыть").url(url))
        val sendResult: Result<SendResponse> = runCatching {
            clients.shopBot.execute(
                SendMessage(userId, text)
                    .disablePreview()
                    .replyMarkup(markup)
            )
        }
        sendResult.onSuccess {
            log.info("price_drop_alert_sent")
        }.onFailure { e ->
            bucket.delete()
            log.warn("price_drop_notify_error item={} cause={}", itemId, e.message)
        }
    }

    private fun buildMessage(priceMinor: Long, currency: String): String {
        val formatted = formatMoney(priceMinor, currency)
        return buildString {
            append(PRICE_DROP_TEXT)
            append('\n')
            append("Новая цена: ").append(formatted)
        }
    }

    private fun buildItemUrl(itemId: String): String {
        val base = cfg.server.publicBaseUrl.trimEnd('/')
        return "$base/app/?item=$itemId"
    }

    private fun formatMoney(amountMinor: Long, currency: String): String {
        val absolute = abs(amountMinor)
        val major = absolute / 100
        val minor = (absolute % 100).toInt().toString().padStart(2, '0')
        val sign = if (amountMinor < 0) "-" else ""
        return "$sign$major.$minor ${currency.uppercase()}"
    }

    private fun redisKey(userId: Long, itemId: String): String =
        "$REDIS_KEY_PREFIX:$userId:$itemId"

    companion object {
        private const val PRICE_DROP_TEXT = "⬇️ Цена снизилась. Товар доступен по новой цене."
        private const val REDIS_KEY_PREFIX = "notify:price_drop"
    }
}

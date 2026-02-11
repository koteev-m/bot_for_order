package com.example.app.services

import com.example.bots.TelegramClients
import com.example.db.OutboxRepository
import com.pengrad.telegrambot.request.SendMessage
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

internal const val TELEGRAM_TEXT_MAX_LEN = 4096

internal fun buildBuyerStatusNotificationMessage(template: String, comment: String?): String {
    val safeTemplate = template.take(TELEGRAM_TEXT_MAX_LEN)
    val note = comment?.takeIf { it.isNotBlank() } ?: return safeTemplate
    val commentPrefix = "Комментарий: "
    val separator = "\n"
    val maxCommentLen = TELEGRAM_TEXT_MAX_LEN - safeTemplate.length - separator.length - commentPrefix.length
    if (maxCommentLen <= 0) {
        return safeTemplate
    }
    val safeComment = if (note.length <= maxCommentLen) {
        note
    } else if (maxCommentLen == 1) {
        "…"
    } else {
        note.take(maxCommentLen - 1) + "…"
    }
    return safeTemplate + separator + commentPrefix + safeComment
}

@Serializable
data class BuyerStatusNotificationPayload(
    val orderId: String,
    val buyerUserId: Long,
    val status: String,
    val comment: String? = null,
    val locale: String? = null
)

class BuyerStatusNotificationOutbox(
    private val outboxRepository: OutboxRepository,
    private val clients: TelegramClients,
    meterRegistry: MeterRegistry? = null
) {
    private val log = LoggerFactory.getLogger(BuyerStatusNotificationOutbox::class.java)
    private val outboxJson = Json { ignoreUnknownKeys = true }
    private val enqueueDoneCounter = meterRegistry?.counter("buyer_status_notification_enqueue_total", "result", "done")
    private val enqueueFailedCounter = meterRegistry?.counter("buyer_status_notification_enqueue_total", "result", "failed")
    private val deliveryDoneCounter = meterRegistry?.counter("buyer_status_notification_delivery_total", "result", "done")
    private val deliveryFailedCounter = meterRegistry?.counter("buyer_status_notification_delivery_total", "result", "failed")
    private val deliverySkippedUnknownStatusCounter =
        meterRegistry?.counter("buyer_status_notification_delivery_total", "result", "skipped_unknown_status")

    suspend fun enqueue(payload: BuyerStatusNotificationPayload, now: Instant) {
        val payloadJson = outboxJson.encodeToString(BuyerStatusNotificationPayload.serializer(), payload)
        runCatching {
            outboxRepository.insert(BUYER_STATUS_NOTIFICATION, payloadJson, now)
        }.onSuccess {
            enqueueDoneCounter?.increment()
            log.info("buyer_status_notification_enqueue_done orderId={} status={}", payload.orderId, payload.status)
        }.onFailure { error ->
            enqueueFailedCounter?.increment()
            log.error(
                "buyer_status_notification_enqueue_failed orderId={} status={} reason={}",
                payload.orderId,
                payload.status,
                error.message,
                error
            )
            throw error
        }
    }

    fun payloadJson(payload: BuyerStatusNotificationPayload): String =
        outboxJson.encodeToString(BuyerStatusNotificationPayload.serializer(), payload)

    suspend fun handle(payloadJson: String) {
        val payload = outboxJson.decodeFromString(BuyerStatusNotificationPayload.serializer(), payloadJson)
        val normalizedStatus = payload.status.trim()
        val template = STATUS_NOTIFICATIONS[normalizedStatus]
        if (template == null) {
            deliverySkippedUnknownStatusCounter?.increment()
            log.debug("buyer_status_notification_unknown_status status={}", normalizedStatus)
            return
        }
        val message = buildBuyerStatusNotificationMessage(template, payload.comment)
        runCatching {
            val response = withContext(Dispatchers.IO) {
                clients.shopBot.execute(SendMessage(payload.buyerUserId, message))
            }
            check(response.isOk) {
                "code=${response.errorCode()} desc=${response.description()}"
            }
        }.onSuccess {
            deliveryDoneCounter?.increment()
            log.info("buyer_status_notification_delivery_done orderId={} status={}", payload.orderId, payload.status)
        }.onFailure { error ->
            deliveryFailedCounter?.increment()
            log.warn(
                "buyer_status_notification_delivery_failed orderId={} status={} reason={}",
                payload.orderId,
                payload.status,
                error.message
            )
            throw error
        }
    }

    companion object {
        const val BUYER_STATUS_NOTIFICATION = "buyer_status_notification"

        private val STATUS_NOTIFICATIONS: Map<String, String> = mapOf(
            "paid" to "✅ Оплата получена. Заказ передан на комплектацию.",
            "PAID_CONFIRMED" to "✅ Оплата получена. Заказ передан на комплектацию.",
            "fulfillment" to "📦 Заказ в комплектации.",
            "shipped" to "🚚 Заказ отправлен.",
            "delivered" to "📬 Заказ доставлен. Спасибо за покупку!",
            "canceled" to "❌ Заказ отменён."
        )
    }
}

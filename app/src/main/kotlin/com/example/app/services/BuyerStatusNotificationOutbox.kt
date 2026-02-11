package com.example.app.services

import com.example.bots.TelegramClients
import com.example.db.OutboxRepository
import com.example.domain.OrderStatus
import com.pengrad.telegrambot.request.SendMessage
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class BuyerStatusNotificationPayload(
    val orderId: String,
    val buyerUserId: Long,
    val status: OrderStatus,
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

    fun handle(payloadJson: String) {
        val payload = outboxJson.decodeFromString(BuyerStatusNotificationPayload.serializer(), payloadJson)
        val template = STATUS_NOTIFICATIONS[payload.status] ?: return
        val message = buildString {
            append(template)
            val note = payload.comment?.takeIf { it.isNotBlank() }
            if (note != null) {
                append('\n')
                append("Комментарий: ")
                append(note)
            }
        }
        runCatching {
            val response = clients.shopBot.execute(SendMessage(payload.buyerUserId, message))
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

        private val STATUS_NOTIFICATIONS: Map<OrderStatus, String> = mapOf(
            OrderStatus.paid to "✅ Оплата получена. Заказ передан на комплектацию.",
            OrderStatus.PAID_CONFIRMED to "✅ Оплата получена. Заказ передан на комплектацию.",
            OrderStatus.fulfillment to "📦 Заказ в комплектации.",
            OrderStatus.shipped to "🚚 Заказ отправлен.",
            OrderStatus.delivered to "📬 Заказ доставлен. Спасибо за покупку!",
            OrderStatus.canceled to "❌ Заказ отменён."
        )
    }
}

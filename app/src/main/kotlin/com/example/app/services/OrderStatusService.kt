package com.example.app.services

import com.example.bots.TelegramClients
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.domain.Order
import com.example.domain.OrderLine
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.hold.HoldService
import com.example.domain.hold.OrderHoldService
import com.example.domain.hold.OrderHoldRequest
import com.pengrad.telegrambot.request.SendMessage
import java.time.Clock
import java.time.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class OrderStatusService(
    private val ordersRepository: OrdersRepository,
    private val orderLinesRepository: OrderLinesRepository,
    private val historyRepository: OrderStatusHistoryRepository,
    private val clients: TelegramClients,
    private val orderHoldService: OrderHoldService,
    private val holdService: HoldService,
    private val clock: Clock = Clock.systemUTC()
) {

    private val log: Logger = LoggerFactory.getLogger(OrderStatusService::class.java)

    suspend fun changeStatus(
        orderId: String,
        newStatus: OrderStatus,
        actorId: Long,
        comment: String?
    ): ChangeResult {
        val order = ordersRepository.get(orderId)
            ?: throw IllegalArgumentException("order not found: $orderId")
        if (order.status == newStatus) {
            return ChangeResult(order = order, changed = false)
        }

        OrderStatusTransitions.requireAllowed(order.status, newStatus)

        ordersRepository.setStatus(orderId, newStatus)
        if (newStatus == OrderStatus.canceled) {
            val lines = orderLinesRepository.listByOrder(orderId)
            orderHoldService.release(orderId, buildOrderHoldRequests(order, lines))
            holdService.deleteReserveByOrder(orderId)
        }
        val entry = OrderStatusEntry(
            id = 0,
            orderId = orderId,
            status = newStatus,
            comment = comment,
            actorId = actorId,
            ts = Instant.now(clock)
        )
        historyRepository.append(entry)
        val fresh = ordersRepository.get(orderId) ?: order.copy(status = newStatus)
        log.info("order status updated orderId={} status={} actorId={}", orderId, newStatus, actorId)
        notifyBuyer(fresh, comment)
        return ChangeResult(order = fresh, changed = true)
    }

    private fun notifyBuyer(order: Order, comment: String?) {
        val template = STATUS_NOTIFICATIONS[order.status] ?: return
        val message = buildString {
            append(template)
            val note = comment?.takeIf { it.isNotBlank() }
            if (note != null) {
                append('\n')
                append("Комментарий: ")
                append(note)
            }
        }
        runCatching {
            clients.shopBot.execute(SendMessage(order.userId, message))
        }.onFailure { error ->
            log.warn(
                "order status notify failed orderId={} status={} reason={}",
                order.id,
                order.status,
                error.message
            )
        }
    }

    data class ChangeResult(
        val order: Order,
        val changed: Boolean
    )

    private fun buildOrderHoldRequests(order: Order, lines: List<OrderLine>): List<OrderHoldRequest> {
        if (lines.isNotEmpty()) {
            return lines
                .groupBy { it.variantId ?: it.listingId }
                .values
                .map { group ->
                    val first = group.first()
                    OrderHoldRequest(
                        listingId = first.listingId,
                        variantId = first.variantId,
                        qty = group.sumOf { it.qty }
                    )
                }
        }
        val itemId = order.itemId ?: return emptyList()
        val qty = order.qty ?: 1
        return listOf(OrderHoldRequest(listingId = itemId, variantId = order.variantId, qty = qty))
    }

    companion object {
        private val STATUS_NOTIFICATIONS: Map<OrderStatus, String> = mapOf(
            OrderStatus.paid to PAID_MESSAGE,
            OrderStatus.PAID_CONFIRMED to PAID_MESSAGE,
            OrderStatus.fulfillment to FULFILLMENT_MESSAGE,
            OrderStatus.shipped to SHIPPED_MESSAGE,
            OrderStatus.delivered to DELIVERED_MESSAGE,
            OrderStatus.canceled to CANCELED_MESSAGE
        )

        private const val PAID_MESSAGE = "✅ Оплата получена. Заказ передан на комплектацию."
        private const val FULFILLMENT_MESSAGE = "📦 Заказ в комплектации."
        private const val SHIPPED_MESSAGE = "🚚 Заказ отправлен."
        private const val DELIVERED_MESSAGE = "📬 Заказ доставлен. Спасибо за покупку!"
        private const val CANCELED_MESSAGE = "❌ Заказ отменён."
    }
}

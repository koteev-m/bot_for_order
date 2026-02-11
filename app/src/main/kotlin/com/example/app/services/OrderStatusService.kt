package com.example.app.services

import com.example.db.EventLogRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrdersRepository
import com.example.domain.EventLogEntry
import com.example.domain.Order
import com.example.domain.OrderLine
import com.example.domain.OrderStatus
import com.example.domain.hold.HoldService
import com.example.domain.hold.OrderHoldRequest
import com.example.domain.hold.OrderHoldService
import java.time.Clock
import java.time.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class OrderStatusService(
    private val ordersRepository: OrdersRepository,
    private val orderLinesRepository: OrderLinesRepository,
    private val orderHoldService: OrderHoldService,
    private val holdService: HoldService,
    private val eventLogRepository: EventLogRepository,
    private val buyerStatusNotificationOutbox: BuyerStatusNotificationOutbox,
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

        val now = Instant.now(clock)
        val payload = BuyerStatusNotificationPayload(
            orderId = order.id,
            buyerUserId = order.userId,
            status = newStatus,
            comment = comment,
            locale = null
        )
        val payloadJson = buyerStatusNotificationOutbox.payloadJson(payload)

        ordersRepository.setStatusWithOutbox(
            id = orderId,
            status = newStatus,
            actorId = actorId,
            comment = comment,
            statusChangedAt = now,
            outboxType = BuyerStatusNotificationOutbox.BUYER_STATUS_NOTIFICATION,
            outboxPayloadJson = payloadJson,
            outboxNow = now
        )

        if (newStatus == OrderStatus.canceled) {
            val lines = orderLinesRepository.listByOrder(orderId)
            orderHoldService.release(orderId, buildOrderHoldRequests(order, lines))
            holdService.deleteReserveByOrder(orderId)
        }

        runCatching {
            eventLogRepository.insert(
                EventLogEntry(
                    ts = now,
                    eventType = "status_changed",
                    buyerUserId = order.userId,
                    merchantId = order.merchantId,
                    storefrontId = null,
                    channelId = null,
                    postMessageId = null,
                    listingId = order.itemId,
                    variantId = order.variantId,
                    metadataJson = """{"status":"${newStatus.name}"}"""
                )
            )
        }.onFailure { error ->
            log.warn(
                "event_log_insert_failed eventType=status_changed orderId={} status={} reason={}",
                orderId,
                newStatus,
                error.message
            )
        }
        val fresh = ordersRepository.get(orderId) ?: order.copy(status = newStatus)
        log.info("order_status_updated orderId={} status={} actorId={} notificationType={}", orderId, newStatus, actorId, BuyerStatusNotificationOutbox.BUYER_STATUS_NOTIFICATION)
        return ChangeResult(order = fresh, changed = true)
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
}

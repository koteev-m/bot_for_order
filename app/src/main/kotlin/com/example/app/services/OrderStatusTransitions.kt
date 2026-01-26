package com.example.app.services

import com.example.domain.OrderStatus

object OrderStatusTransitions {
    private val allowed: Map<OrderStatus, Set<OrderStatus>> = mapOf(
        OrderStatus.pending to setOf(
            OrderStatus.paid,
            OrderStatus.canceled,
            OrderStatus.AWAITING_PAYMENT_DETAILS,
            OrderStatus.AWAITING_PAYMENT
        ),
        OrderStatus.AWAITING_PAYMENT_DETAILS to setOf(
            OrderStatus.AWAITING_PAYMENT,
            OrderStatus.canceled
        ),
        OrderStatus.AWAITING_PAYMENT to setOf(
            OrderStatus.PAYMENT_UNDER_REVIEW,
            OrderStatus.canceled
        ),
        OrderStatus.PAYMENT_UNDER_REVIEW to setOf(
            OrderStatus.PAID_CONFIRMED,
            OrderStatus.AWAITING_PAYMENT,
            OrderStatus.AWAITING_PAYMENT_DETAILS,
            OrderStatus.canceled
        ),
        OrderStatus.PAID_CONFIRMED to setOf(
            OrderStatus.fulfillment,
            OrderStatus.canceled
        ),
        OrderStatus.paid to setOf(OrderStatus.fulfillment, OrderStatus.canceled),
        OrderStatus.fulfillment to setOf(OrderStatus.shipped, OrderStatus.canceled),
        OrderStatus.shipped to setOf(OrderStatus.delivered, OrderStatus.canceled),
        OrderStatus.delivered to emptySet(),
        OrderStatus.canceled to emptySet()
    )

    fun isAllowed(from: OrderStatus, to: OrderStatus): Boolean =
        allowed[from].orEmpty().contains(to)

    fun requireAllowed(from: OrderStatus, to: OrderStatus) {
        require(isAllowed(from, to)) { "transition $from -> $to is not allowed" }
    }
}

package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.db.MerchantsRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.OrderLine
import com.example.domain.hold.OrderHoldService
import com.example.domain.hold.OrderHoldRequest
import com.pengrad.telegrambot.request.SendMessage
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val RESERVE_EXPIRED_MESSAGE = "⏳ Резерв заказа %s истёк. Начните оформление заново."

data class ReservesSweepDeps(
    val ordersRepository: OrdersRepository,
    val orderLinesRepository: OrderLinesRepository,
    val merchantsRepository: MerchantsRepository,
    val historyRepository: OrderStatusHistoryRepository,
    val orderHoldService: OrderHoldService,
    val clients: TelegramClients,
    val sweepIntervalSec: Int,
    val clock: Clock = Clock.systemUTC(),
    val log: Logger = LoggerFactory.getLogger(ReservesSweepJob::class.java)
)

class ReservesSweepJob(
    private val application: Application,
    private val deps: ReservesSweepDeps
) {

    private val ordersRepository = deps.ordersRepository
    private val orderLinesRepository = deps.orderLinesRepository
    private val merchantsRepository = deps.merchantsRepository
    private val historyRepository = deps.historyRepository
    private val orderHoldService = deps.orderHoldService
    private val clients = deps.clients
    private val sweepIntervalSec = deps.sweepIntervalSec
    private val clock = deps.clock
    private val log = deps.log

    fun start() {
        if (sweepIntervalSec <= 0) {
            log.warn("reserves_sweep_disabled interval={}", sweepIntervalSec)
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ReservesSweepJob"))
        application.environment.monitor.subscribe(ApplicationStopped) { scope.cancel() }
        val intervalMs = sweepIntervalSec.toLong() * 1_000L
        scope.launch {
            while (isActive) {
                runCatching { sweepOnce() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.warn("reserves_sweep_failed: ${error.message}", error)
                    }
                delay(intervalMs)
            }
        }
    }

    internal suspend fun runOnceForTests() {
        sweepOnce()
    }

    private suspend fun sweepOnce() {
        val now = clock.instant()
        val claimCandidates = ordersRepository.listPendingClaimOlderThan(now)
        claimCandidates.forEach { order ->
            val merchant = merchantsRepository.getById(order.merchantId) ?: return@forEach
            val claimDeadline = order.createdAt.plusSeconds(merchant.paymentClaimWindowSeconds.toLong())
            if (now.isAfter(claimDeadline)) {
                cancelOrder(order.id, order.userId, "payment_timeout")
            }
        }

        val reviewCandidates = ordersRepository.listPendingReviewOlderThan(now)
        reviewCandidates.forEach { order ->
            val merchant = merchantsRepository.getById(order.merchantId) ?: return@forEach
            val claimedAt = order.paymentClaimedAt ?: return@forEach
            val reviewDeadline = claimedAt.plusSeconds(merchant.paymentReviewWindowSeconds.toLong())
            if (now.isAfter(reviewDeadline)) {
                cancelOrder(order.id, order.userId, "payment_review_timeout")
            }
        }
    }

    private suspend fun cancelOrder(orderId: String, userId: Long, reason: String) {
        ordersRepository.setStatus(orderId, OrderStatus.canceled)
        historyRepository.append(
            OrderStatusEntry(
                id = 0,
                orderId = orderId,
                status = OrderStatus.canceled,
                comment = reason,
                actorId = null,
                ts = Instant.now(clock)
            )
        )
        val lines = orderLinesRepository.listByOrder(orderId)
        orderHoldService.release(orderId, buildOrderHoldRequests(lines))
        notifyBuyer(userId, orderId)
        log.info("order_payment_timeout orderId={} user={} reason={}", orderId, userId, reason)
    }

    private fun notifyBuyer(userId: Long, orderId: String) {
        val text = RESERVE_EXPIRED_MESSAGE.format(orderId)
        runCatching {
            clients.shopBot.execute(SendMessage(userId, text))
        }.onFailure { error ->
            log.warn(
                "reserve_expired_notify_failed orderId={} reason={}",
                orderId,
                error.message
            )
        }
    }
}

private fun buildOrderHoldRequests(lines: List<OrderLine>): List<OrderHoldRequest> {
    if (lines.isEmpty()) return emptyList()
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

fun Application.installReservesSweepJob(cfg: AppConfig) {
    val ordersRepository by inject<OrdersRepository>()
    val orderLinesRepository by inject<OrderLinesRepository>()
    val merchantsRepository by inject<MerchantsRepository>()
    val orderStatusRepository by inject<OrderStatusHistoryRepository>()
    val orderHoldService by inject<OrderHoldService>()
    val clients by inject<TelegramClients>()
    val deps = ReservesSweepDeps(
        ordersRepository = ordersRepository,
        orderLinesRepository = orderLinesRepository,
        merchantsRepository = merchantsRepository,
        historyRepository = orderStatusRepository,
        orderHoldService = orderHoldService,
        clients = clients,
        sweepIntervalSec = cfg.server.reservesSweepSec,
        log = environment.log
    )
    ReservesSweepJob(this, deps).start()
}

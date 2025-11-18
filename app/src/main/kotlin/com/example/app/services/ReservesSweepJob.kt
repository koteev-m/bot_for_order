package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.hold.HoldService
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
    val historyRepository: OrderStatusHistoryRepository,
    val holdService: HoldService,
    val clients: TelegramClients,
    val sweepIntervalSec: Int,
    val orderReserveTtlSec: Int,
    val clock: Clock = Clock.systemUTC(),
    val log: Logger = LoggerFactory.getLogger(ReservesSweepJob::class.java)
)

class ReservesSweepJob(
    private val application: Application,
    private val deps: ReservesSweepDeps
) {

    private val ordersRepository = deps.ordersRepository
    private val historyRepository = deps.historyRepository
    private val holdService = deps.holdService
    private val clients = deps.clients
    private val sweepIntervalSec = deps.sweepIntervalSec
    private val orderReserveTtlSec = deps.orderReserveTtlSec
    private val clock = deps.clock
    private val log = deps.log

    fun start() {
        if (orderReserveTtlSec <= 0 || sweepIntervalSec <= 0) {
            log.warn("reserves_sweep_disabled ttl={} interval={}", orderReserveTtlSec, sweepIntervalSec)
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

    private suspend fun sweepOnce() {
        holdService.releaseExpired()
        val cutoff = clock.instant().minusSeconds(orderReserveTtlSec.toLong())
        val staleOrders = ordersRepository.listPendingOlderThan(cutoff)
        if (staleOrders.isEmpty()) {
            return
        }
        staleOrders.forEach { order ->
            val hasReserve = holdService.hasOrderReserve(order.id)
            if (hasReserve) {
                return@forEach
            }
            cancelOrder(order)
        }
    }

    private suspend fun cancelOrder(order: Order) {
        ordersRepository.setStatus(order.id, OrderStatus.canceled)
        historyRepository.append(
            OrderStatusEntry(
                id = 0,
                orderId = order.id,
                status = OrderStatus.canceled,
                comment = "reserve_expired",
                actorId = null,
                ts = Instant.now(clock)
            )
        )
        holdService.deleteReserveByOrder(order.id)
        notifyBuyer(order)
        log.info(
            "order_reserve_expired orderId={} user={} item={} variant={}",
            order.id,
            order.userId,
            order.itemId,
            order.variantId
        )
    }

    private fun notifyBuyer(order: Order) {
        val text = RESERVE_EXPIRED_MESSAGE.format(order.id)
        runCatching {
            clients.shopBot.execute(SendMessage(order.userId, text))
        }.onFailure { error ->
            log.warn(
                "reserve_expired_notify_failed orderId={} reason={}",
                order.id,
                error.message
            )
        }
    }
}

fun Application.installReservesSweepJob(cfg: AppConfig) {
    val ordersRepository by inject<OrdersRepository>()
    val orderStatusRepository by inject<OrderStatusHistoryRepository>()
    val holdService by inject<HoldService>()
    val clients by inject<TelegramClients>()
    val deps = ReservesSweepDeps(
        ordersRepository = ordersRepository,
        historyRepository = orderStatusRepository,
        holdService = holdService,
        clients = clients,
        sweepIntervalSec = cfg.server.reservesSweepSec,
        orderReserveTtlSec = cfg.server.orderReserveTtlSec,
        log = environment.log
    )
    ReservesSweepJob(this, deps).start()
}

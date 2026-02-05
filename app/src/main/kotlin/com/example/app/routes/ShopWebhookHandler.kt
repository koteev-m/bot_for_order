package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.services.ORDER_PAYLOAD_PREFIX
import com.example.app.services.PaymentsService
import com.example.app.tg.TgMessage
import com.example.app.tg.TgSuccessfulPayment
import com.example.app.tg.TgUpdate
import com.example.app.tg.TgPreCheckoutQuery
import com.example.app.tg.TgShippingQuery
import com.example.bots.TelegramClients
import com.example.bots.startapp.StartAppCodec
import com.example.db.ItemsRepository
import com.example.db.MerchantsRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.db.TelegramWebhookDedupRepository
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldService
import com.pengrad.telegrambot.model.request.ShippingOption
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

fun Application.installShopWebhook() {
    val cfg by inject<AppConfig>()
    val clients by inject<TelegramClients>()
    val itemsRepo by inject<ItemsRepository>()
    val pricesRepo by inject<PricesDisplayRepository>()
    val variantsRepo by inject<VariantsRepository>()
    val ordersRepo by inject<OrdersRepository>()
    val orderLinesRepo by inject<OrderLinesRepository>()
    val merchantsRepo by inject<MerchantsRepository>()
    val orderStatusRepo by inject<OrderStatusHistoryRepository>()
    val paymentsService by inject<PaymentsService>()
    val lockManager by inject<LockManager>()
    val orderHoldService by inject<OrderHoldService>()
    val holdService by inject<HoldService>()
    val webhookDedupRepository by inject<TelegramWebhookDedupRepository>()

    val deps = ShopWebhookDeps(
        config = cfg,
        clients = clients,
        itemsRepository = itemsRepo,
        pricesRepository = pricesRepo,
        variantsRepository = variantsRepo,
        ordersRepository = ordersRepo,
        orderLinesRepository = orderLinesRepo,
        merchantsRepository = merchantsRepo,
        orderStatusRepository = orderStatusRepo,
        paymentsService = paymentsService,
        lockManager = lockManager,
        orderHoldService = orderHoldService,
        holdService = holdService,
        webhookDedupRepository = webhookDedupRepository,
        json = Json { ignoreUnknownKeys = true }
    )

    routing {
        post("/tg/shop") {
            if (!verifyTelegramWebhookSecret(call, deps.config.telegram.shopWebhookSecret)) {
                return@post
            }
            val body = call.receiveText()
            handleShopUpdate(call, body, deps)
        }
    }
}

internal data class ShopWebhookDeps(
    val config: AppConfig,
    val clients: TelegramClients,
    val itemsRepository: ItemsRepository,
    val pricesRepository: PricesDisplayRepository,
    val variantsRepository: VariantsRepository,
    val ordersRepository: OrdersRepository,
    val orderLinesRepository: OrderLinesRepository,
    val merchantsRepository: MerchantsRepository,
    val orderStatusRepository: OrderStatusHistoryRepository,
    val paymentsService: PaymentsService,
    val lockManager: LockManager,
    val orderHoldService: OrderHoldService,
    val holdService: HoldService,
    val webhookDedupRepository: TelegramWebhookDedupRepository,
    val json: Json
)

private const val PRECHECK_TIMEOUT_MS = 9_000L
private const val PAYMENT_LOCK_WAIT_MS = 2_000L
private const val PAYMENT_LOCK_LEASE_MS = 15_000L
private const val SHIPPING_DISABLED_MESSAGE = "shipping_unavailable"
private const val SHIPPING_REGION_BLOCKED_MESSAGE = "region_not_supported"
private const val SHIPPING_INVALID_PAYLOAD_MESSAGE = "invalid_payload"
private const val SHIPPING_ORDER_ERROR_MESSAGE = "order_unavailable"
private const val PRECHECK_GENERIC_ERROR = "payment_validation_failed"
private const val PRECHECK_AMOUNT_ERROR = "amount_mismatch"
private const val PRECHECK_STATUS_ERROR = "order_status_invalid"
private const val PRECHECK_CURRENCY_ERROR = "currency_mismatch"
private const val PRECHECK_RESERVE_ERROR = "reserve_expired"
private const val PAYMENT_CONFIRMED_REPLY = "✅ Оплата получена! Заказ <code>%s</code> передан в обработку."
private const val PAYMENT_ALREADY_CONFIRMED_REPLY = "ℹ️ Заказ <code>%s</code> уже оплачен."
private const val PAYMENT_UNKNOWN_ORDER_REPLY = "❌ Заказ не найден. Проверьте ID или свяжитесь с поддержкой."
private const val PAYMENT_STOCK_FAILED_REPLY =
    "❌ Не удалось завершить оплату: товар закончился. Попробуйте оформить заново."

private data class ShippingDecision(
    val ok: Boolean,
    val options: List<ShippingOption> = emptyList(),
    val errorMessage: String? = null,
    val logMessage: String,
    val warn: Boolean
)

private data class PreCheckoutDecision(
    val ok: Boolean,
    val errorMessage: String? = null,
    val logMessage: String,
    val warn: Boolean,
    val throwable: Throwable? = null
)

private suspend fun handleShopUpdate(call: ApplicationCall, body: String, deps: ShopWebhookDeps) {
    val update = runCatching { deps.json.decodeFromString(TgUpdate.serializer(), body) }.getOrNull()
    if (update == null) {
        call.respond(HttpStatusCode.OK)
        return
    }

    val acquireDecision = acquireTelegramUpdateProcessing(
        dedupRepository = deps.webhookDedupRepository,
        botType = TELEGRAM_BOT_TYPE_SHOP,
        updateId = update.updateId,
        logger = shopLog
    )
    when (acquireDecision) {
        TelegramWebhookDedupDecision.ALREADY_PROCESSED -> {
            call.respond(HttpStatusCode.OK)
            return
        }
        TelegramWebhookDedupDecision.IN_PROGRESS -> {
            call.respondTelegramInProgress()
            return
        }
        TelegramWebhookDedupDecision.ACQUIRED -> Unit
    }

    runCatching {
        val message = update.message
        val text = message?.text?.trim().orEmpty()
        when {
            update.shippingQuery != null -> handleShippingQuery(update.shippingQuery, deps)
            update.preCheckoutQuery != null -> handlePreCheckoutQuery(update.preCheckoutQuery, deps)
            message?.successfulPayment != null -> handleSuccessfulPayment(message, message.successfulPayment, deps)
            message == null || !text.startsWith("/") -> {
                return@runCatching
            }
            else -> {
                val chatId = message.chat.id
                val (command, args) = splitCommand(text)
                when (command) {
                    "/start" -> handleStart(chatId, args, deps)
                    "/open" -> handleOpen(chatId, args, deps)
                    else -> {
                        // ignore
                    }
                }
            }
        }
    }.onSuccess {
        markTelegramUpdateProcessedBestEffort(
            dedupRepository = deps.webhookDedupRepository,
            botType = TELEGRAM_BOT_TYPE_SHOP,
            updateId = update.updateId,
            logger = shopLog
        )
        call.respond(HttpStatusCode.OK)
    }.onFailure { error ->
        releaseTelegramUpdateProcessingBestEffort(
            dedupRepository = deps.webhookDedupRepository,
            botType = TELEGRAM_BOT_TYPE_SHOP,
            updateId = update.updateId,
            logger = shopLog
        )
        shopLog.error("tg_shop_webhook_processing_failed updateId={}", update.updateId, error)
        throw error
    }
}

private suspend fun verifyTelegramWebhookSecret(call: ApplicationCall, expected: String): Boolean {
    val provided = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
    if (provided.isNullOrBlank() || provided != expected) {
        call.respond(HttpStatusCode.Unauthorized)
        return false
    }
    return true
}

private suspend fun handleStart(chatId: Long, args: String, deps: ShopWebhookDeps) {
    if (args.isBlank()) {
        deps.clients.replyShopHtml(chatId, WELCOME_MESSAGE)
        return
    }

    val param = runCatching { StartAppCodec.decode(args) }.getOrElse {
        deps.clients.replyShopHtml(chatId, INVALID_PARAM_MESSAGE)
        return
    }

    sendItemCard(chatId = chatId, itemId = param.itemId, deps = deps)
}

private suspend fun handleOpen(chatId: Long, args: String, deps: ShopWebhookDeps) {
    val itemId = args.ifBlank { null }
    if (itemId == null) {
        deps.clients.replyShopHtml(chatId, USAGE_MESSAGE)
        return
    }

    sendItemCard(chatId = chatId, itemId = itemId, deps = deps)
}

private suspend fun handleShippingQuery(query: TgShippingQuery, deps: ShopWebhookDeps) {
    val orderId = parseOrderIdFromPayload(query.invoicePayload)
    val region = query.shippingAddress.countryCode.uppercase()
    val allowlist = deps.config.payments.shippingRegionAllowlist
    val order = orderId?.let { deps.ordersRepository.get(it) }

    val decision = when {
        orderId == null -> ShippingDecision(
            ok = false,
            logMessage = "shipping_query invalid payload",
            errorMessage = SHIPPING_INVALID_PAYLOAD_MESSAGE,
            warn = true
        )
        !deps.config.payments.shippingEnabled -> ShippingDecision(
            ok = false,
            logMessage = "shipping_query disabled orderId=$orderId",
            errorMessage = SHIPPING_DISABLED_MESSAGE,
            warn = true
        )
        order == null || order.status != OrderStatus.pending -> ShippingDecision(
            ok = false,
            logMessage = "shipping_query order unavailable orderId=$orderId",
            errorMessage = SHIPPING_ORDER_ERROR_MESSAGE,
            warn = true
        )
        allowlist.isNotEmpty() && !allowlist.contains(region) -> ShippingDecision(
            ok = false,
            logMessage = "shipping_query region blocked orderId=$orderId region=$region",
            errorMessage = SHIPPING_REGION_BLOCKED_MESSAGE,
            warn = true
        )
        else -> ShippingDecision(
            ok = true,
            options = buildShippingOptions(deps.config),
            logMessage = "shipping_query accepted orderId=$orderId region=$region",
            warn = false
        )
    }

    deps.paymentsService.answerShipping(query.id, decision.options, ok = decision.ok, errorText = decision.errorMessage)
    if (decision.warn) {
        shopLog.warn(decision.logMessage)
    } else {
        shopLog.info(decision.logMessage)
    }
}

private suspend fun handlePreCheckoutQuery(query: TgPreCheckoutQuery, deps: ShopWebhookDeps) {
    val orderId = parseOrderIdFromPayload(query.invoicePayload)
    if (orderId == null) {
        deps.paymentsService.answerPreCheckout(query.id, ok = false, errorText = SHIPPING_INVALID_PAYLOAD_MESSAGE)
        shopLog.warn("pre_checkout invalid payload")
        return
    }

    val decision = try {
        withTimeout(PRECHECK_TIMEOUT_MS) {
            evaluatePreCheckout(query, deps, orderId)
        }
    } catch (timeout: TimeoutCancellationException) {
        PreCheckoutDecision(
            ok = false,
            errorMessage = PRECHECK_GENERIC_ERROR,
            logMessage = "pre_checkout timeout orderId=$orderId",
            warn = true,
            throwable = timeout
        )
    }

    deps.paymentsService.answerPreCheckout(query.id, ok = decision.ok, errorText = decision.errorMessage)
    if (decision.warn) {
        shopLog.warn(decision.logMessage)
        decision.throwable?.let { shopLog.error("pre_checkout failure orderId={}", orderId, it) }
    } else {
        shopLog.info(decision.logMessage)
    }
}

private suspend fun handleSuccessfulPayment(
    message: TgMessage,
    payment: TgSuccessfulPayment,
    deps: ShopWebhookDeps
) {
    val orderId = parseOrderIdFromPayload(payment.invoicePayload)
    val chatId = message.chat.id
    if (orderId == null) {
        deps.clients.replyShopHtml(chatId, PAYMENT_UNKNOWN_ORDER_REPLY)
        shopLog.warn("successful_payment invalid payload")
        return
    }

    var reply = PAYMENT_UNKNOWN_ORDER_REPLY
    try {
        deps.lockManager.withLock("order:$orderId", PAYMENT_LOCK_WAIT_MS, PAYMENT_LOCK_LEASE_MS) {
            val order = deps.ordersRepository.get(orderId)
            if (order == null) {
                reply = PAYMENT_UNKNOWN_ORDER_REPLY
                shopLog.warn("successful_payment order missing orderId={}", orderId)
                return@withLock
            }
            if (order.status == OrderStatus.paid) {
                reply = PAYMENT_ALREADY_CONFIRMED_REPLY.format(orderId)
                return@withLock
            }
            val lines = deps.orderLinesRepository.listByOrder(orderId)
            val stockOk = decrementStock(order, lines, deps)
            if (!stockOk) {
                handleStockFailure(order, lines, deps)
                reply = PAYMENT_STOCK_FAILED_REPLY
                return@withLock
            }
            deps.ordersRepository.markPaid(
                orderId,
                provider = "telegram",
                providerChargeId = payment.providerPaymentChargeId,
                telegramPaymentChargeId = payment.telegramPaymentChargeId
            )
            deps.orderStatusRepository.append(
                OrderStatusEntry(
                    id = 0,
                    orderId = orderId,
                    status = OrderStatus.paid,
                    comment = "telegram_payment",
                    actorId = message.from?.id,
                    ts = Instant.now()
                )
            )
            deps.orderHoldService.release(orderId, buildOrderHoldRequests(order, lines))
            deps.holdService.deleteReserveByOrder(orderId)
            reply = PAYMENT_CONFIRMED_REPLY.format(orderId)
            shopLog.info(
                "order paid orderId={} item={} variant={} qty={}",
                orderId,
                order.itemId ?: "n/a",
                order.variantId,
                order.qty ?: 0
            )
        }
    } catch (error: IllegalStateException) {
        reply = PAYMENT_UNKNOWN_ORDER_REPLY
        shopLog.error("successful_payment handler failed orderId={}", orderId, error)
    }

    deps.clients.replyShopHtml(chatId, reply)
}

private suspend fun evaluatePreCheckout(
    query: TgPreCheckoutQuery,
    deps: ShopWebhookDeps,
    orderId: String
): PreCheckoutDecision {
    val order = deps.ordersRepository.get(orderId)
    val expectedCurrency = deps.config.payments.invoiceCurrency.uppercase()
    return when {
        order == null -> PreCheckoutDecision(
            ok = false,
            errorMessage = SHIPPING_ORDER_ERROR_MESSAGE,
            logMessage = "pre_checkout order missing orderId=$orderId",
            warn = true
        )
        order.status != OrderStatus.pending -> PreCheckoutDecision(
            ok = false,
            errorMessage = PRECHECK_STATUS_ERROR,
            logMessage = "pre_checkout status invalid orderId=$orderId status=${order.status}",
            warn = true
        )
        query.currency.uppercase() != expectedCurrency -> PreCheckoutDecision(
            ok = false,
            errorMessage = PRECHECK_CURRENCY_ERROR,
            logMessage = "pre_checkout currency mismatch orderId=$orderId currency=${query.currency}",
            warn = true
        )
        order.amountMinor != query.totalAmount -> PreCheckoutDecision(
            ok = false,
            errorMessage = PRECHECK_AMOUNT_ERROR,
            logMessage = "pre_checkout amount mismatch orderId=$orderId " +
                "expected=${order.amountMinor} actual=${query.totalAmount}",
            warn = true
        )
        else -> {
            val lines = deps.orderLinesRepository.listByOrder(orderId)
            val holdRequests = buildOrderHoldRequests(order, lines)
            val holdsActive = deps.orderHoldService.hasActive(orderId, holdRequests) ||
                deps.holdService.hasOrderReserve(orderId)
            if (!holdsActive) {
                return PreCheckoutDecision(
                    ok = false,
                    errorMessage = PRECHECK_RESERVE_ERROR,
                    logMessage = "pre_checkout reserve missing orderId=$orderId",
                    warn = true
                )
            }
            val merchant = deps.merchantsRepository.getById(order.merchantId)
                ?: return PreCheckoutDecision(
                    ok = false,
                    errorMessage = PRECHECK_GENERIC_ERROR,
                    logMessage = "pre_checkout merchant missing orderId=$orderId",
                    warn = true
                )
            val now = Instant.now()
            val claimedNow = deps.ordersRepository.setPaymentClaimed(orderId, now)
            val claimedAt = if (claimedNow) {
                now
            } else {
                deps.ordersRepository.get(orderId)?.paymentClaimedAt ?: now
            }
            val reviewDeadline = claimedAt.plusSeconds(merchant.paymentReviewWindowSeconds.toLong())
            val ttlRemaining = kotlin.math.max(
                java.time.Duration.between(now, reviewDeadline).seconds,
                1L
            )
            deps.orderHoldService.extend(orderId, holdRequests, ttlRemaining)
            PreCheckoutDecision(
                ok = true,
                logMessage = "pre_checkout approved orderId=$orderId",
                warn = false
            )
        }
    }
}


private val WELCOME_MESSAGE = """
    👋 Добро пожаловать! Отправьте ссылку с параметром или команду <code>/open &lt;ITEM_ID&gt;</code>.
""".trimIndent()

private const val INVALID_PARAM_MESSAGE = "⚠️ Неверный параметр запуска."

private const val USAGE_MESSAGE = "Использование: <code>/open &lt;ITEM_ID&gt;</code>"

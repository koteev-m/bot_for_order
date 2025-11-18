package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.db.ItemsRepository
import com.example.db.OffersRepository
import com.example.db.OrdersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.domain.BargainRules
import com.example.domain.ItemStatus
import com.example.domain.Offer
import com.example.domain.OfferDecision
import com.example.domain.OfferStatus
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.Variant
import com.example.domain.evaluateOffer
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.ReserveKind
import com.example.domain.hold.ReserveKey
import com.example.domain.hold.ReservePayload
import com.pengrad.telegrambot.model.LinkPreviewOptions
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.SendResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import org.redisson.api.RedissonClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val OFFER_LOCK_WAIT_MS = 200L
private const val OFFER_LOCK_LEASE_MS = 3_000L
private const val OFFER_QTY_MIN = 1
private const val COOLDOWN_KEY_PREFIX = "cooldown:offer"
private const val ACCEPT_BUTTON_TEXT = "Принять"

data class OfferRepositories(
    val items: ItemsRepository,
    val variants: VariantsRepository,
    val prices: PricesDisplayRepository,
    val offers: OffersRepository,
    val orders: OrdersRepository
)

data class OfferServicesDeps(
    val holdService: HoldService,
    val lockManager: LockManager,
    val redisson: RedissonClient,
    val paymentsService: PaymentsService,
    val clients: TelegramClients,
    val config: AppConfig,
    val clock: Clock = Clock.systemUTC()
)

private data class OfferContext(
    val userId: Long,
    val itemId: String,
    val variantId: String?,
    val qty: Int,
    val offerMinor: Long,
    val basePriceMinor: Long,
    val rules: BargainRules
) {
    val ttlSec: Long = rules.ttlSec.toLong()
    val cooldownSec: Long = rules.cooldownSec.toLong()
    val cooldownKey: String = buildCooldownKey(itemId, variantId, userId)
}

enum class OfferDecisionType(val apiValue: String) {
    AUTO_ACCEPT("autoAccept"),
    COUNTER("counter"),
    REJECT("reject")
}

data class OfferResult(
    val decision: OfferDecisionType,
    val counterAmountMinor: Long? = null,
    val ttlSec: Long? = null
)

data class CounterResult(
    val offerId: String,
    val lastCounterAmount: Long,
    val ttlSec: Long
)

data class OfferAcceptResult(
    val orderId: String,
    val status: OrderStatus
)

class OffersService(
    private val repositories: OfferRepositories,
    private val deps: OfferServicesDeps
) {

    private val holdService = deps.holdService
    private val lockManager = deps.lockManager
    private val redisson = deps.redisson
    private val paymentsService = deps.paymentsService
    private val clients = deps.clients
    private val config = deps.config
    private val clock = deps.clock

    private val log = LoggerFactory.getLogger(OffersService::class.java)

    suspend fun createAndEvaluate(
        userId: Long,
        itemId: String,
        variantId: String?,
        qty: Int,
        offerMinor: Long
    ): OfferResult {
        require(qty >= OFFER_QTY_MIN) { "qty must be >= $OFFER_QTY_MIN" }
        require(offerMinor > 0) { "offerMinor must be > 0" }

        val item = requireNotNull(repositories.items.getById(itemId)) { "item not found" }
        check(item.status == ItemStatus.active) { "item is not active" }
        check(item.allowBargain) { "bargain disabled for item" }
        val rules = requireNotNull(item.bargainRules) { "bargain rules missing" }
        check(rules.ttlSec > 0) { "ttlSec must be > 0" }
        check(rules.cooldownSec >= 0) { "cooldownSec must be >= 0" }

        val basePrice = requireNotNull(repositories.prices.get(itemId)?.baseAmountMinor) { "base price missing" }
        require(basePrice > 0) { "base price must be > 0" }

        val variant = variantId?.let { loadVariant(repositories, itemId, it, qty) }
        val context = OfferContext(
            userId = userId,
            itemId = itemId,
            variantId = variant?.id,
            qty = qty,
            offerMinor = offerMinor,
            basePriceMinor = basePrice,
            rules = rules
        )

        val lockKey = buildOfferLockKey(userId, itemId, context.variantId)
        return lockManager.withLock(lockKey, OFFER_LOCK_WAIT_MS, OFFER_LOCK_LEASE_MS) {
            processOffer(context)
        }
    }

    suspend fun adminCounter(offerId: String, amountMinor: Long, adminId: Long): CounterResult {
        require(amountMinor > 0) { "amountMinor must be > 0" }
        val offer = requireNotNull(repositories.offers.get(offerId)) { "offer not found" }
        val item = checkNotNull(repositories.items.getById(offer.itemId)) { "item not found" }
        check(item.status == ItemStatus.active) { "item is not active" }
        check(item.allowBargain) { "bargain disabled for item" }
        val rules = checkNotNull(item.bargainRules) { "bargain rules missing" }
        require(rules.ttlSec > 0) { "ttlSec must be > 0" }

        val now = clock.instant()
        val expiresAt = checkNotNull(offer.expiresAt) { "offer missing TTL" }
        require(expiresAt.isAfter(now)) { "offer expired" }
        require(offer.status == OfferStatus.new || offer.status == OfferStatus.countered) {
            "offer status ${offer.status} cannot be countered"
        }
        require(offer.countersUsed < rules.maxCounters) { "counter limit reached" }

        val ttlSec = rules.ttlSec.toLong()
        val newExpiresAt = now.plusSeconds(ttlSec)
        repositories.offers.updateCounter(offerId, amountMinor, newExpiresAt)
        sendCounterOfferMessage(offer.userId, amountMinor, item.title, offerId)
        log.info(
            "offer_admin_counter id={} admin={} amount={} ttl={}s",
            offerId,
            adminId,
            amountMinor,
            ttlSec
        )
        return CounterResult(offerId = offerId, lastCounterAmount = amountMinor, ttlSec = ttlSec)
    }

    suspend fun acceptOffer(userId: Long, offerId: String, qty: Int): OfferAcceptResult {
        require(qty >= OFFER_QTY_MIN) { "qty must be >= $OFFER_QTY_MIN" }
        val offer = requireNotNull(repositories.offers.get(offerId)) { "offer not found" }
        require(offer.userId == userId) { "offer not found" }
        val status = offer.status
        require(status == OfferStatus.auto_accept || status == OfferStatus.countered) {
            "offer status ${offer.status} cannot be accepted"
        }
        val expiresAt = checkNotNull(offer.expiresAt) { "offer missing TTL" }
        val now = clock.instant()
        require(expiresAt.isAfter(now)) { "offer expired" }

        val item = checkNotNull(repositories.items.getById(offer.itemId)) { "item not found" }
        check(item.status == ItemStatus.active) { "item is not active" }
        offer.variantId?.let { loadVariant(repositories, offer.itemId, it, qty) }

        val amountMinor = when (status) {
            OfferStatus.auto_accept -> offer.lastCounterAmount ?: offer.offerAmountMinor
            OfferStatus.countered -> checkNotNull(offer.lastCounterAmount) { "counter offer missing amount" }
            else -> error("unsupported status $status")
        }
        require(amountMinor > 0) { "amount must be > 0" }

        val currency = config.payments.invoiceCurrency.uppercase()
        val order = Order(
            id = generateOrderId(userId),
            userId = userId,
            itemId = offer.itemId,
            variantId = offer.variantId,
            qty = qty,
            currency = currency,
            amountMinor = amountMinor,
            deliveryOption = null,
            addressJson = null,
            provider = null,
            providerChargeId = null,
            telegramPaymentChargeId = null,
            invoiceMessageId = null,
            status = OrderStatus.pending,
            updatedAt = now
        )
        repositories.orders.create(order)
        paymentsService.createAndSendInvoice(order, item.title, photoUrl = null)
        repositories.offers.updateStatusAndCounters(
            offerId,
            OfferStatus.accepted,
            offer.countersUsed,
            offer.lastCounterAmount ?: amountMinor,
            offer.expiresAt
        )
        log.info("offer_accept id={} user={} order={} qty={}", offerId, userId, order.id, qty)
        return OfferAcceptResult(order.id, order.status)
    }

    private suspend fun processOffer(context: OfferContext): OfferResult {
        val now = clock.instant()
        val guardDecision = when {
            isCooldownActive(context.cooldownKey) -> OfferDecision
                .Reject(OfferDecision.REASON_COOLDOWN_ACTIVE)
                .toResult()
            else -> repositories.offers
                .findActiveByUserAndItem(context.userId, context.itemId, context.variantId)
                ?.toOfferResult(now)
        }
        if (guardDecision != null) {
            return guardDecision
        }

        val expiresAt = now.plusSeconds(context.ttlSec)
        val offer = Offer(
            id = UUID.randomUUID().toString(),
            itemId = context.itemId,
            variantId = context.variantId,
            userId = context.userId,
            offerAmountMinor = context.offerMinor,
            status = OfferStatus.new,
            countersUsed = 0,
            expiresAt = expiresAt,
            lastCounterAmount = null
        )
        repositories.offers.create(offer)
        log.info(
            "offer_created id={} item={} variant={} qty={}",
            offer.id,
            context.itemId,
            context.variantId,
            context.qty
        )
        activateCooldown(context.cooldownKey, context.cooldownSec)

        val decision = evaluateOffer(context.basePriceMinor, context.offerMinor, context.rules, 0)
        return when (decision) {
            is OfferDecision.AutoAccept -> handleAutoAccept(offer, decision, expiresAt, context)
            is OfferDecision.Counter -> handleCounter(offer, decision, expiresAt, context)
            is OfferDecision.Reject -> handleReject(offer, decision, expiresAt, context)
        }
    }

    private suspend fun handleAutoAccept(
        offer: Offer,
        decision: OfferDecision.AutoAccept,
        expiresAt: Instant,
        context: OfferContext
    ): OfferResult {
        repositories.offers.updateStatusAndCounters(
            offer.id,
            OfferStatus.auto_accept,
            countersUsed = 0,
            lastCounterAmount = decision.amountMinor,
            expiresAt = expiresAt
        )
        createHold(holdService, clock, log, offer.id, context)
        log.info(
            "offer_decision id={} type=auto_accept item={} variant={} qty={}",
            offer.id,
            context.itemId,
            context.variantId,
            context.qty
        )
        return OfferResult(OfferDecisionType.AUTO_ACCEPT, ttlSec = context.ttlSec)
    }

    private suspend fun handleCounter(
        offer: Offer,
        decision: OfferDecision.Counter,
        expiresAt: Instant,
        context: OfferContext
    ): OfferResult {
        repositories.offers.updateStatusAndCounters(
            offer.id,
            OfferStatus.countered,
            countersUsed = 1,
            lastCounterAmount = decision.amountMinor,
            expiresAt = expiresAt
        )
        log.info(
            "offer_decision id={} type=counter item={} variant={} qty={}",
            offer.id,
            context.itemId,
            context.variantId,
            context.qty
        )
        return OfferResult(
            decision = OfferDecisionType.COUNTER,
            counterAmountMinor = decision.amountMinor,
            ttlSec = context.ttlSec
        )
    }

    private suspend fun handleReject(
        offer: Offer,
        decision: OfferDecision.Reject,
        expiresAt: Instant,
        context: OfferContext
    ): OfferResult {
        repositories.offers.updateStatusAndCounters(
            offer.id,
            OfferStatus.declined,
            countersUsed = 0,
            lastCounterAmount = null,
            expiresAt = expiresAt
        )
        log.info(
            "offer_decision id={} type=reject reason={} item={} variant={} qty={}",
            offer.id,
            decision.reason,
            context.itemId,
            context.variantId,
            context.qty
        )
        return OfferResult(OfferDecisionType.REJECT)
    }

    private suspend fun isCooldownActive(key: String): Boolean = withContext(Dispatchers.IO) {
        redisson.getBucket<String>(key).isExists
    }

    private suspend fun activateCooldown(key: String, ttlSec: Long) {
        if (ttlSec <= 0) return
        withContext(Dispatchers.IO) {
            redisson.getBucket<String>(key).set("1", ttlSec, TimeUnit.SECONDS)
        }
    }

    private fun sendCounterOfferMessage(userId: Long, amountMinor: Long, itemTitle: String, offerId: String) {
        val amountDisplay = formatMoney(amountMinor, config.payments.invoiceCurrency)
        val text = buildString {
            appendLine(
                "🤝 Продавец предложил цену <b>${escapeHtml(amountDisplay)}</b> по товару «${escapeHtml(itemTitle)}»."
            )
            append("Нажмите «Принять», чтобы оформить заказ.")
        }
        val acceptUrl = buildAcceptUrl(config.server.publicBaseUrl, offerId)
        val markup = InlineKeyboardMarkup(InlineKeyboardButton(ACCEPT_BUTTON_TEXT).url(acceptUrl))
        val previewOptions = LinkPreviewOptions().isDisabled(true)
        val request = SendMessage(userId, text)
            .parseMode(ParseMode.HTML)
            .linkPreviewOptions(previewOptions)
            .replyMarkup(markup)
        val response: SendResponse = clients.shopBot.execute(request)
        if (!response.isOk) {
            error("failed to deliver counter offer: ${response.errorCode()} ${response.description()}")
        }
    }

}

private fun buildOfferLockKey(userId: Long, itemId: String, variantId: String?): String {
    val variantKey = variantId ?: "_"
    return "offer:new:$userId:$itemId:$variantKey"
}

private fun buildCooldownKey(itemId: String, variantId: String?, userId: Long): String {
    val variantKey = variantId ?: "_"
    return "$COOLDOWN_KEY_PREFIX:$itemId:$variantKey:$userId"
}

private fun buildAcceptUrl(baseUrl: String, offerId: String): String {
    val base = baseUrl.trimEnd('/')
    val encoded = URLEncoder.encode(offerId, StandardCharsets.UTF_8)
    return "$base/app/?offer=$encoded&action=accept"
}

private suspend fun loadVariant(
    repositories: OfferRepositories,
    itemId: String,
    variantId: String,
    qty: Int
): Variant {
    val variants = repositories.variants.listByItem(itemId)
    val variant = requireNotNull(variants.firstOrNull { it.id == variantId }) {
        "variantId does not belong to item"
    }
    require(variant.active) { "variant not available" }
    require(variant.stock >= qty) { "qty exceeds stock" }
    return variant
}

private suspend fun createHold(
    holdService: HoldService,
    clock: Clock,
    log: Logger,
    offerId: String,
    context: OfferContext
) {
    if (context.ttlSec <= 0) return
    val key = ReserveKey(ReserveKind.OFFER, offerId)
    val payload = ReservePayload(
        itemId = context.itemId,
        variantId = context.variantId,
        qty = context.qty,
        userId = context.userId,
        createdAtEpochSec = clock.instant().epochSecond,
        ttlSec = context.ttlSec
    )
    val created = holdService.putIfAbsent(key, payload, context.ttlSec)
    if (!created) {
        log.warn(
            "offer_hold_exists id={} item={} variant={} qty={}",
            offerId,
            context.itemId,
            context.variantId,
            context.qty
        )
    }
}

private fun OfferDecision.Reject.toResult(): OfferResult = OfferResult(OfferDecisionType.REJECT)

private fun Offer.toOfferResult(now: Instant): OfferResult {
    val remainingTtl = expiresAt?.let { expiry ->
        val seconds = Duration.between(now, expiry).seconds
        if (seconds > 0) seconds else null
    }
    return when (status) {
        OfferStatus.auto_accept -> OfferResult(OfferDecisionType.AUTO_ACCEPT, ttlSec = remainingTtl)
        OfferStatus.countered -> {
            val amount = lastCounterAmount
                ?: error("counter offer $id missing lastCounterAmount")
            OfferResult(
                OfferDecisionType.COUNTER,
                counterAmountMinor = amount,
                ttlSec = remainingTtl
            )
        }
        else -> OfferResult(OfferDecisionType.REJECT)
    }
}

private fun formatMoney(amountMinor: Long, currency: String): String {
    val absAmount = abs(amountMinor)
    val major = absAmount / 100
    val minor = (absAmount % 100).toInt()
    val number = "%d.%02d".format(major, minor)
    val sign = if (amountMinor < 0) "-" else ""
    return "$sign$number ${currency.uppercase()}"
}

private fun escapeHtml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun generateOrderId(userId: Long): String = "ord_${System.currentTimeMillis()}_$userId"

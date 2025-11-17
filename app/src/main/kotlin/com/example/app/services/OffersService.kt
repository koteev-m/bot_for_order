package com.example.app.services

import com.example.db.ItemsRepository
import com.example.db.OffersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.domain.BargainRules
import com.example.domain.ItemStatus
import com.example.domain.Offer
import com.example.domain.OfferDecision
import com.example.domain.OfferStatus
import com.example.domain.Variant
import com.example.domain.evaluateOffer
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.ReserveKind
import com.example.domain.hold.ReserveKey
import com.example.domain.hold.ReservePayload
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory

private const val OFFER_LOCK_WAIT_MS = 200L
private const val OFFER_LOCK_LEASE_MS = 3_000L
private const val OFFER_QTY_MIN = 1
private const val COOLDOWN_KEY_PREFIX = "cooldown:offer"

data class OfferRepositories(
    val items: ItemsRepository,
    val variants: VariantsRepository,
    val prices: PricesDisplayRepository,
    val offers: OffersRepository
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

class OffersService(
    private val repositories: OfferRepositories,
    private val holdService: HoldService,
    private val lockManager: LockManager,
    private val redisson: RedissonClient,
    private val clock: Clock = Clock.systemUTC()
) {

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

        val variant = variantId?.let { loadVariant(itemId, it, qty) }
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
            expiresAtIso = expiresAt.toString(),
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
        createHold(offer.id, context)
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

    private suspend fun loadVariant(itemId: String, variantId: String, qty: Int): Variant {
        val variants = repositories.variants.listByItem(itemId)
        val variant = variants.firstOrNull { it.id == variantId }
            ?: throw IllegalArgumentException("variantId does not belong to item")
        require(variant.active) { "variant not available" }
        require(variant.stock >= qty) { "qty exceeds stock" }
        return variant
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

    private suspend fun createHold(offerId: String, context: OfferContext) {
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
}

private fun buildOfferLockKey(userId: Long, itemId: String, variantId: String?): String {
    val variantKey = variantId ?: "_"
    return "offer:new:$userId:$itemId:$variantKey"
}

private fun buildCooldownKey(itemId: String, variantId: String?, userId: Long): String {
    val variantKey = variantId ?: "_"
    return "$COOLDOWN_KEY_PREFIX:$itemId:$variantKey:$userId"
}

private fun OfferDecision.Reject.toResult(): OfferResult = OfferResult(OfferDecisionType.REJECT)

private fun Offer.toOfferResult(now: Instant): OfferResult {
    val remainingTtl = expiresAtIso?.let { expires ->
        val expiresAt = runCatching { Instant.parse(expires) }.getOrNull()
        if (expiresAt != null) {
            val seconds = Duration.between(now, expiresAt).seconds
            if (seconds > 0) seconds else null
        } else {
            null
        }
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

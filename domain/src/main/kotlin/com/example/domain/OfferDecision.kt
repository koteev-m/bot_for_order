package com.example.domain

import kotlin.math.max

sealed interface OfferDecision {
    data class AutoAccept(val amountMinor: Long) : OfferDecision
    data class Counter(val amountMinor: Long) : OfferDecision
    data class Reject(val reason: String) : OfferDecision {
        init {
            require(reason in VALID_REASONS) { "Invalid reject reason: $reason" }
        }
    }

    companion object {
        const val REASON_TOO_LOW: String = "too_low"
        const val REASON_LIMIT_REACHED: String = "limit_reached"
        const val REASON_COOLDOWN_ACTIVE: String = "cooldown_active"
        const val REASON_INVALID: String = "invalid"

        private val VALID_REASONS = setOf(
            REASON_TOO_LOW,
            REASON_LIMIT_REACHED,
            REASON_COOLDOWN_ACTIVE,
            REASON_INVALID
        )
    }
}

fun evaluateOffer(
    baseMinor: Long,
    offerMinor: Long,
    rules: BargainRules,
    countersUsed: Int
): OfferDecision {
    require(baseMinor > 0) { "baseMinor must be > 0" }
    require(offerMinor > 0) { "offerMinor must be > 0" }

    val scaledOffer = Math.multiplyExact(offerMinor, 100L)
    val ratio = scaledOffer / baseMinor
    val discountPct = 100L - ratio

    val decision = when {
        discountPct <= rules.minAcceptPct.toLong() -> OfferDecision.AutoAccept(offerMinor)
        discountPct <= rules.minVisiblePct.toLong() && countersUsed < rules.maxCounters -> {
            val counterPct = (100 - (rules.minAcceptPct + rules.autoCounterStepPct))
                .coerceIn(0, 100)
            val scaledBase = Math.multiplyExact(baseMinor, counterPct.toLong())
            val counterAmount = max(1L, scaledBase / 100)
            OfferDecision.Counter(counterAmount)
        }
        else -> null
    }

    return decision ?: OfferDecision.Reject(OfferDecision.REASON_TOO_LOW)
}

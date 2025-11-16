package com.example.domain

import java.time.Instant

data class FxSnapshot(
    val ts: Instant,
    val scale: Int = DEFAULT_SCALE,
    val rates: Map<String, Long>,
    val source: String = "stub"
) {
    init {
        require(scale > 0) { "scale must be positive" }
    }

    companion object {
        const val DEFAULT_SCALE: Int = 1_000_000
    }
}

interface FxService {
    fun current(): FxSnapshot

    suspend fun refresh(): FxSnapshot

    fun convert(amountMinor: Long, from: String, to: String, scale: Int = current().scale): Long {
        val snapshot = current()
        val normalizedFrom = from.uppercase()
        val normalizedTo = to.uppercase()
        if (normalizedFrom == normalizedTo) {
            return amountMinor
        }
        val conversionScale = if (scale > 0) scale else snapshot.scale
        val rates = snapshot.rates
        val directKey = rateKey(normalizedFrom, normalizedTo)
        val directRate = rates[directKey]
        return if (directRate != null) {
            val numerator = multiplyExact(amountMinor, directRate)
            (numerator + conversionScale / 2L) / conversionScale
        } else {
            val inverseKey = rateKey(normalizedTo, normalizedFrom)
            val inverseRate = rates[inverseKey]
                ?: error("Missing FX rate for $directKey")
            val numerator = multiplyExact(amountMinor, conversionScale.toLong())
            (numerator + inverseRate / 2L) / inverseRate
        }
    }

    private fun rateKey(from: String, to: String): String = "$from/$to"

    private fun multiplyExact(a: Long, b: Long): Long =
        try {
            Math.multiplyExact(a, b)
        } catch (e: ArithmeticException) {
            throw IllegalStateException("Overflow when converting currencies", e)
        }
}

interface DisplayPriceService {
    suspend fun recomputeItem(itemId: String)

    suspend fun recomputeAllActive()
}

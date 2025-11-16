package com.example.app.services

import com.example.domain.FxService
import com.example.domain.FxSnapshot
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class FxServiceStub(
    knownCurrencies: Set<String>,
    private val clock: Clock = Clock.systemUTC()
) : FxService {

    private val currencyUniverse = knownCurrencies.map(String::uppercase).toSet() + DEFAULT_CURRENCIES
    private val snapshotRef = AtomicReference(buildSnapshot(readEnvRates()))
    private val scale: Int = FxSnapshot.DEFAULT_SCALE

    override fun current(): FxSnapshot = snapshotRef.get()

    override suspend fun refresh(): FxSnapshot {
        val envRates = readEnvRates()
        val snapshot = buildSnapshot(envRates)
        snapshotRef.set(snapshot)
        return snapshot
    }

    private fun buildSnapshot(envRates: Map<String, Long>): FxSnapshot {
        val defaults = computeDefaultRates()
        val rates = defaults.toMutableMap()
        envRates.forEach { (pair, rate) ->
            rates[pair] = rate
            computeInverse(pair, rate)?.let { (inversePair, inverseRate) ->
                rates[inversePair] = inverseRate
            }
        }
        return FxSnapshot(
            ts = Instant.now(clock),
            scale = scale,
            rates = rates.toMap(),
            source = if (envRates.isEmpty()) "stub" else "env"
        )
    }

    private fun computeDefaultRates(): Map<String, Long> {
        val anchors = DEFAULT_ANCHORS
        val currencies = (currencyUniverse + anchors.keys).map(String::uppercase).toSet()
        val rates = mutableMapOf<String, Long>()
        currencies.forEach { from ->
            val fromAnchor = anchors[from] ?: return@forEach
            currencies.forEach { to ->
                if (from == to) return@forEach
                val toAnchor = anchors[to] ?: return@forEach
                val numerator = multiplyExact(toAnchor.toLong(), scale.toLong())
                val rate = (numerator + fromAnchor / 2) / fromAnchor
                rates[rateKey(from, to)] = rate
            }
        }
        return rates
    }

    private fun readEnvRates(): Map<String, Long> {
        val env = System.getenv()
        val result = mutableMapOf<String, Long>()
        env.forEach { (key, rawValue) ->
            if (!key.startsWith(RATE_PREFIX)) return@forEach
            val suffix = key.removePrefix(RATE_PREFIX)
            val (from, to) = parsePair(suffix)
            val sanitized = rawValue.trim().replace("_", "")
            val rate = sanitized.toLongOrNull()
                ?: error("$key must be a long value in micros")
            require(rate > 0) { "$key must be positive" }
            result[rateKey(from, to)] = rate
        }
        return result
    }

    private fun parsePair(raw: String): Pair<String, String> {
        val value = raw.uppercase()
        val candidates = mutableListOf<Pair<String, String>>()
        value.indices.filter { value[it] == '_' }.forEach { idx ->
            val from = value.substring(0, idx)
            val to = value.substring(idx + 1)
            if (from.isNotBlank() && to.isNotBlank()) {
                candidates += from to to
            }
        }
        val known = candidates.firstOrNull { (from, to) ->
            currencyUniverse.contains(from) && currencyUniverse.contains(to)
        }
        val pair = known ?: candidates.firstOrNull()
            ?: error("$raw must contain '_' separator")
        return pair.first to pair.second
    }

    private fun computeInverse(pair: String, rate: Long): Pair<String, Long>? {
        val parts = pair.split('/')
        if (parts.size != 2 || rate <= 0) {
            return null
        }
        val numerator = multiplyExact(scale.toLong(), scale.toLong())
        val inverse = (numerator + rate / 2) / rate
        return rateKey(parts[1], parts[0]) to inverse
    }

    private fun multiplyExact(a: Long, b: Long): Long =
        try {
            Math.multiplyExact(a, b)
        } catch (e: ArithmeticException) {
            throw IllegalStateException("Overflow when calculating FX rate", e)
        }

    private fun rateKey(from: String, to: String): String = "$from/$to"

    companion object {
        private const val RATE_PREFIX = "FX_RATE_"
        private val DEFAULT_CURRENCIES = setOf("USD", "EUR", "RUB", "USDT_TS")
        private val DEFAULT_ANCHORS = mapOf(
            "USD" to 1_000_000L,
            "EUR" to 925_000L,
            "RUB" to 96_500_000L,
            "USDT_TS" to 1_000_000L
        )
    }
}

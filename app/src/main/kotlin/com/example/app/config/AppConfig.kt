package com.example.app.config

data class AppConfig(
    val telegram: TelegramConfig,
    val telegramInitData: TelegramInitDataConfig,
    val merchants: MerchantsConfig,
    val linkContext: LinkContextConfig,
    val linkResolveRateLimit: LinkResolveRateLimitConfig,
    val cart: CartConfig,
    val db: DbConfig,
    val redis: RedisConfig,
    val payments: PaymentsConfig,
    val server: ServerConfig,
    val fx: FxConfig,
    val logging: LoggingConfig,
    val metrics: MetricsConfig,
    val health: HealthConfig,
    val security: SecurityConfig,
)

data class TelegramConfig(
    val adminToken: String,
    val shopToken: String,
    val adminIds: Set<Long>,
    val channelId: Long,
    val buyerMiniAppShortName: String
)

data class TelegramInitDataConfig(
    val maxAgeSeconds: Long
)

data class MerchantsConfig(
    val defaultMerchantId: String
)

data class LinkContextConfig(
    val tokenSecret: String
)

data class LinkResolveRateLimitConfig(
    val max: Int,
    val windowSeconds: Int
)

data class CartConfig(
    val undoTtlSec: Int,
    val addDedupWindowSec: Int
)

data class DbConfig(
    val url: String,
    val user: String,
    val password: String
)

data class RedisConfig(
    val url: String
)

data class PaymentsConfig(
    val providerToken: String,
    val invoiceCurrency: String,
    val allowTips: Boolean,
    val suggestedTipAmountsMinor: List<Int>,
    val shippingEnabled: Boolean,
    val shippingRegionAllowlist: Set<String>,
    val shippingBaseStdMinor: Long,
    val shippingBaseExpMinor: Long
)

data class ServerConfig(
    val publicBaseUrl: String,
    val offersExpireSweepSec: Int,
    val offerReserveTtlSec: Int,
    val orderReserveTtlSec: Int,
    val reservesSweepSec: Int,
    val reserveStockLockSec: Int,
    val watchlistPriceDropEnabled: Boolean,
    val priceDropNotifyCooldownSec: Int,
    val priceDropMinAbsMinor: Long,
    val priceDropMinRelPct: Double,
    val watchlistRestockEnabled: Boolean,
    val restockNotifyCooldownSec: Int,
    val restockNotifyConsume: Boolean,
    val restockScanSec: Int
)

data class FxConfig(
    val displayCurrencies: Set<String>,
    val refreshIntervalSec: Int
)

data class LoggingConfig(
    val level: String,
    val json: Boolean
)

data class HstsConfig(
    val enabled: Boolean = false,
    val maxAgeSeconds: Long = 15_552_000,
    val includeSubdomains: Boolean = true,
    val preload: Boolean = false,
)

data class SecurityConfig(
    val hsts: HstsConfig = HstsConfig(),
    val basicAuthCompat: BasicAuthCompatConfig = BasicAuthCompatConfig(),
)

data class BasicAuthCompatConfig(
    val latin1Fallback: Boolean = false,
)

data class BasicAuth(
    val user: String,
    val password: String,
)

data class MetricsConfig(
    val enabled: Boolean,
    val prometheusEnabled: Boolean,
    val basicAuth: BasicAuth?,
    val basicRealm: String = "metrics",
    val ipAllowlist: Set<String>,
    val trustedProxyAllowlist: Set<String>,
)

data class HealthConfig(
    val dbTimeoutMs: Long,
    val redisTimeoutMs: Long
)

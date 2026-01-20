package com.example.app.config

import java.net.URI

object ConfigLoader {

    fun fromEnv(): AppConfig = AppConfig(
        telegram = loadTelegramConfig(),
        merchants = loadMerchantsConfig(),
        linkContext = loadLinkContextConfig(),
        db = loadDbConfig(),
        redis = loadRedisConfig(),
        payments = loadPaymentsConfig(),
        server = loadServerConfig(),
        fx = loadFxConfig(),
        logging = loadLoggingConfig(),
        metrics = loadMetricsConfig(),
        health = loadHealthConfig(),
        security = loadSecurityConfig(),
    )

    private fun loadTelegramConfig(): TelegramConfig {
        val adminToken = requireNonBlank("ADMIN_BOT_TOKEN")
        val shopToken = requireNonBlank("SHOP_BOT_TOKEN")
        val adminIds = parseAdminIds(requireNonBlank("ADMIN_IDS"))
        val channelId = parseLongEnv("CHANNEL_ID")
        return TelegramConfig(
            adminToken = adminToken,
            shopToken = shopToken,
            adminIds = adminIds,
            channelId = channelId
        )
    }

    private fun loadMerchantsConfig(): MerchantsConfig {
        val defaultMerchantId = System.getenv("DEFAULT_MERCHANT_ID")
            ?.takeIf { it.isNotBlank() }
            ?: "default"
        return MerchantsConfig(defaultMerchantId = defaultMerchantId)
    }

    private fun loadLinkContextConfig(): LinkContextConfig = LinkContextConfig(
        tokenSecret = requireNonBlank("LINK_TOKEN_SECRET")
    )

    private fun loadDbConfig(): DbConfig = DbConfig(
        url = requireNonBlank("DATABASE_URL"),
        user = requireNonBlank("DATABASE_USER"),
        password = requireNonBlank("DATABASE_PASSWORD")
    )

    private fun loadRedisConfig(): RedisConfig {
        val redisUrl = requireNonBlank("REDIS_URL")
        validateRedisUrl(redisUrl)
        return RedisConfig(url = redisUrl)
    }

    private fun loadServerConfig(): ServerConfig {
        val publicBaseUrl = requireNonBlank("PUBLIC_BASE_URL")
        validateHttps(publicBaseUrl)
        val offersExpireSweepSec = parsePositiveIntEnv("OFFERS_EXPIRE_SWEEP_SEC", defaultValue = 30)
        val offerReserveTtlSec = parsePositiveIntEnv("OFFER_RESERVE_TTL_SEC", defaultValue = 900)
        val orderReserveTtlSec = parsePositiveIntEnv("ORDER_RESERVE_TTL_SEC", defaultValue = 1_800)
        val reservesSweepSec = parsePositiveIntEnv("RESERVES_SWEEP_SEC", defaultValue = 60)
        val reserveStockLockSec = parsePositiveIntEnv("RESERVE_STOCK_LOCK_SEC", defaultValue = 5)
        val watchlistEnabled = parseBooleanEnv("WATCHLIST_PRICE_DROP_ENABLED", defaultValue = true)
        val priceDropCooldown = parsePositiveIntEnv("PRICE_DROP_NOTIFY_COOLDOWN_SEC", defaultValue = 86_400)
        val priceDropMinAbsMinor = parseNonNegativeLongEnv("PRICE_DROP_MIN_ABS_MINOR", defaultValue = 0)
        val priceDropMinRelPct = parseNonNegativeDoubleEnv("PRICE_DROP_MIN_REL_PCT", defaultValue = 0.0)
        val restockWatchEnabled = parseBooleanEnv("WATCHLIST_RESTOCK_ENABLED", defaultValue = true)
        val restockCooldown = parsePositiveIntEnv("RESTOCK_NOTIFY_COOLDOWN_SEC", defaultValue = 172_800)
        val restockConsume = parseBooleanEnv("RESTOCK_NOTIFY_CONSUME", defaultValue = true)
        val restockScanSec = parsePositiveIntEnv("RESTOCK_SCAN_SEC", defaultValue = 60)
        return ServerConfig(
            publicBaseUrl = publicBaseUrl,
            offersExpireSweepSec = offersExpireSweepSec,
            offerReserveTtlSec = offerReserveTtlSec,
            orderReserveTtlSec = orderReserveTtlSec,
            reservesSweepSec = reservesSweepSec,
            reserveStockLockSec = reserveStockLockSec,
            watchlistPriceDropEnabled = watchlistEnabled,
            priceDropNotifyCooldownSec = priceDropCooldown,
            priceDropMinAbsMinor = priceDropMinAbsMinor,
            priceDropMinRelPct = priceDropMinRelPct,
            watchlistRestockEnabled = restockWatchEnabled,
            restockNotifyCooldownSec = restockCooldown,
            restockNotifyConsume = restockConsume,
            restockScanSec = restockScanSec
        )
    }

    private fun loadPaymentsConfig(): PaymentsConfig {
        val providerToken = requireNonBlank("PROVIDER_TOKEN")
        val invoiceCurrency = requireNonBlank("INVOICE_CURRENCY").uppercase()
        val allowTips = parseBooleanEnv("PAYMENTS_ALLOW_TIPS", defaultValue = false)
        val tipSuggestions = parseTipSuggestions(System.getenv("PAYMENTS_TIP_SUGGESTED"))
        val shippingEnabled = parseBooleanEnv("SHIPPING_ENABLED", defaultValue = false)
        val shippingAllowlist = parseCountryAllowlist(System.getenv("SHIPPING_REGION_ALLOWLIST"))
        val shippingStdMinor = parseMinorAmount(System.getenv("SHIPPING_BASE_STD_MINOR"))
        val shippingExpMinor = parseMinorAmount(System.getenv("SHIPPING_BASE_EXP_MINOR"))
        return PaymentsConfig(
            providerToken = providerToken,
            invoiceCurrency = invoiceCurrency,
            allowTips = allowTips,
            suggestedTipAmountsMinor = tipSuggestions,
            shippingEnabled = shippingEnabled,
            shippingRegionAllowlist = shippingAllowlist,
            shippingBaseStdMinor = shippingStdMinor,
            shippingBaseExpMinor = shippingExpMinor
        )
    }

    private fun loadFxConfig(): FxConfig {
        val displayCurrencies = parseDisplayCurrencies(System.getenv("FX_DISPLAY_CURRENCIES"))
        val refreshIntervalSec = parseRefreshInterval(System.getenv("FX_REFRESH_INTERVAL_SEC"))
        return FxConfig(
            displayCurrencies = displayCurrencies,
            refreshIntervalSec = refreshIntervalSec
        )
    }

    private fun loadLoggingConfig(): LoggingConfig {
        val level = parseLogLevelEnv("LOG_LEVEL", defaultValue = "INFO")
        val json = parseBooleanEnv("LOG_JSON", defaultValue = true)
        return LoggingConfig(level = level, json = json)
    }

    private fun loadMetricsConfig(): MetricsConfig {
        val enabled = parseBooleanEnv("METRICS_ENABLED", defaultValue = true)
        val prometheus = parseBooleanEnv("PROMETHEUS_ENABLED", defaultValue = true)
        val basicAuth = parseBasicAuthEnv("METRICS_BASIC_AUTH")
        val ipAllowlist = parseIpAllowlistEnv("METRICS_IP_ALLOWLIST")
        val trustedProxyAllowlist = parseIpAllowlistEnv("METRICS_TRUSTED_PROXY_ALLOWLIST")
        val basicRealm = System.getenv("METRICS_BASIC_REALM")?.takeIf { it.isNotBlank() } ?: "metrics"
        return MetricsConfig(
            enabled = enabled,
            prometheusEnabled = prometheus,
            basicAuth = basicAuth,
            basicRealm = basicRealm,
            ipAllowlist = ipAllowlist,
            trustedProxyAllowlist = trustedProxyAllowlist
        )
    }

    private fun loadHealthConfig(): HealthConfig = HealthConfig(
        dbTimeoutMs = parsePositiveLongEnv("HEALTH_DB_TIMEOUT_MS", defaultValue = 500),
        redisTimeoutMs = parsePositiveLongEnv("HEALTH_REDIS_TIMEOUT_MS", defaultValue = 500)
    )

    private fun loadSecurityConfig(): SecurityConfig {
        val hsts = HstsConfig(
            enabled = parseBooleanEnv("SECURITY_HSTS_ENABLED", defaultValue = false),
            maxAgeSeconds = parseNonNegativeLongEnv("SECURITY_HSTS_MAX_AGE", defaultValue = 15_552_000),
            includeSubdomains = parseBooleanEnv("SECURITY_HSTS_INCLUDE_SUBDOMAINS", defaultValue = true),
            preload = parseBooleanEnv("SECURITY_HSTS_PRELOAD", defaultValue = false),
        )
        val basicAuthCompat = BasicAuthCompatConfig(
            latin1Fallback = parseBooleanEnv("SECURITY_BASIC_AUTH_LATIN1_FALLBACK", defaultValue = false),
        )
        return SecurityConfig(
            hsts = hsts,
            basicAuthCompat = basicAuthCompat,
        )
    }
}

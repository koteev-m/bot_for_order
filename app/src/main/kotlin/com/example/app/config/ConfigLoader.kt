package com.example.app.config

import java.net.URI

object ConfigLoader {

    fun fromEnv(): AppConfig = AppConfig(
        telegram = loadTelegramConfig(),
        db = loadDbConfig(),
        redis = loadRedisConfig(),
        payments = loadPaymentsConfig(),
        server = loadServerConfig(),
        fx = loadFxConfig()
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
            priceDropMinRelPct = priceDropMinRelPct
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
}

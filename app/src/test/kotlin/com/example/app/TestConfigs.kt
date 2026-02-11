package com.example.app

import com.example.app.config.AppConfig
import com.example.app.config.CartConfig
import com.example.app.config.DbConfig
import com.example.app.config.FxConfig
import com.example.app.config.HealthConfig
import com.example.app.config.LinkContextConfig
import com.example.app.config.LinkResolveRateLimitConfig
import com.example.app.config.LoggingConfig
import com.example.app.config.ManualPaymentsConfig
import com.example.app.config.OutboxConfig
import com.example.app.config.MerchantsConfig
import com.example.app.config.MetricsConfig
import com.example.app.config.PaymentsConfig
import com.example.app.config.RedisConfig
import com.example.app.config.RetentionConfig
import com.example.app.config.RetentionPiiConfig
import com.example.app.config.RetentionTechnicalConfig
import com.example.app.config.SecurityConfig
import com.example.app.config.ServerConfig
import com.example.app.config.StorageConfig
import com.example.app.config.TelegramConfig
import com.example.app.config.TelegramInitDataConfig
import com.example.app.config.UserActionRateLimitConfig

internal fun baseTestConfig(
    metrics: MetricsConfig = MetricsConfig(
        enabled = true,
        prometheusEnabled = true,
        basicAuth = null,
        ipAllowlist = emptySet(),
        trustedProxyAllowlist = emptySet()
    ),
    health: HealthConfig = HealthConfig(dbTimeoutMs = 50, redisTimeoutMs = 50),
    security: SecurityConfig = SecurityConfig(),
): AppConfig = AppConfig(
    telegram = TelegramConfig(
        adminToken = "token",
        shopToken = "token",
        adminWebhookSecret = "admin-secret",
        shopWebhookSecret = "shop-secret",
        adminIds = emptySet(),
        channelId = 0L,
        buyerMiniAppShortName = "buyer"
    ),
    telegramInitData = TelegramInitDataConfig(maxAgeSeconds = 86_400),
    merchants = MerchantsConfig(defaultMerchantId = "default"),
    linkContext = LinkContextConfig(tokenSecret = "test-secret"),
    linkResolveRateLimit = LinkResolveRateLimitConfig(max = 10, windowSeconds = 10),
    userActionRateLimit = UserActionRateLimitConfig(
        resolveMax = 30,
        resolveWindowSeconds = 10,
        addMax = 20,
        addWindowSeconds = 10,
        claimMax = 5,
        claimWindowSeconds = 60
    ),
    cart = CartConfig(undoTtlSec = 300, addDedupWindowSec = 5),
    db = DbConfig(
        url = "jdbc:postgresql://localhost:5432/db",
        user = "user",
        password = "pass"
    ),
    redis = RedisConfig(url = "redis://localhost:6379"),
    payments = PaymentsConfig(
        providerToken = "provider",
        invoiceCurrency = "USD",
        allowTips = false,
        suggestedTipAmountsMinor = emptyList(),
        shippingEnabled = false,
        shippingRegionAllowlist = emptySet(),
        shippingBaseStdMinor = 0,
        shippingBaseExpMinor = 0
    ),
    manualPayments = ManualPaymentsConfig(
        detailsEncryptionKey = ByteArray(32) { 1 }
    ),
    storage = StorageConfig(
        endpoint = "http://localhost",
        region = "us-east-1",
        bucket = "test",
        accessKey = "key",
        secretKey = "secret",
        presignTtlSeconds = 300,
        pathPrefix = null
    ),
    server = ServerConfig(
        publicBaseUrl = "http://localhost",
        offersExpireSweepSec = 0,
        offerReserveTtlSec = 0,
        orderReserveTtlSec = 0,
        reservesSweepSec = 0,
        reserveStockLockSec = 0,
        watchlistPriceDropEnabled = false,
        priceDropNotifyCooldownSec = 0,
        priceDropMinAbsMinor = 0,
        priceDropMinRelPct = 0.0,
        watchlistRestockEnabled = false,
        restockNotifyCooldownSec = 0,
        restockNotifyConsume = false,
        restockScanSec = 0
    ),
    fx = FxConfig(
        displayCurrencies = emptySet(),
        refreshIntervalSec = 0
    ),
    logging = LoggingConfig(
        level = "INFO",
        json = true
    ),
    metrics = metrics,
    health = health,
    security = security,
    outbox = OutboxConfig(
        enabled = true,
        pollIntervalMs = 50,
        batchSize = 10,
        maxAttempts = 5,
        baseBackoffMs = 100,
        maxBackoffMs = 5_000,
        processingTtlMs = 600_000
    ),
    retention = RetentionConfig(
        purgeEnabled = true,
        intervalHours = 24,
        pii = RetentionPiiConfig(
            auditLogDays = 30,
            orderDeliveryDays = 180
        ),
        technical = RetentionTechnicalConfig(
            outboxDays = 30,
            webhookDedupDays = 30,
            idempotencyDays = 14
        )
    )
)

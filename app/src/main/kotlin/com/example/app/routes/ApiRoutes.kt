package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.services.CartService
import com.example.app.services.DeliveryService
import com.example.app.services.LinkResolveService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.OffersService
import com.example.app.services.OrderCheckoutService
import com.example.app.services.PaymentsService
import com.example.app.services.UserActionRateLimiter
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.app.security.installInitDataAuth
import com.example.app.security.TelegramInitDataVerifier
import com.example.domain.watchlist.WatchlistRepository
import com.example.db.EventLogRepository
import com.example.app.services.IdempotencyService
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.installApiRoutes() {
    val itemsRepo by inject<ItemsRepository>()
    val mediaRepo by inject<ItemMediaRepository>()
    val variantsRepo by inject<VariantsRepository>()
    val pricesRepo by inject<PricesDisplayRepository>()
    val ordersRepo by inject<OrdersRepository>()
    val orderLinesRepo by inject<OrderLinesRepository>()
    val historyRepo by inject<OrderStatusHistoryRepository>()
    val paymentsService by inject<PaymentsService>()
    val manualPaymentsService by inject<ManualPaymentsService>()
    val deliveryService by inject<DeliveryService>()
    val offersService by inject<OffersService>()
    val cfg by inject<AppConfig>()
    val initDataVerifier by inject<TelegramInitDataVerifier>()
    val watchlistRepo by inject<WatchlistRepository>()
    val linkResolveService by inject<LinkResolveService>()
    val userActionRateLimiter by inject<UserActionRateLimiter>()
    val cartService by inject<CartService>()
    val orderCheckoutService by inject<OrderCheckoutService>()
    val orderDeliveryRepository by inject<OrderDeliveryRepository>()
    val idempotencyService by inject<IdempotencyService>()
    val eventLogRepository by inject<EventLogRepository>()

    val orderDeps = OrderRoutesDeps(
        merchantId = cfg.merchants.defaultMerchantId,
        itemsRepository = itemsRepo,
        ordersRepository = ordersRepo,
        orderLinesRepository = orderLinesRepo,
        historyRepository = historyRepo,
        paymentsService = paymentsService,
        orderCheckoutService = orderCheckoutService,
        manualPaymentsService = manualPaymentsService,
        orderDeliveryRepository = orderDeliveryRepository,
        deliveryService = deliveryService,
        idempotencyService = idempotencyService,
        userActionRateLimiter = userActionRateLimiter
    )

    routing {
        route("/api") {
            installInitDataAuth(initDataVerifier)
            registerItemRoutes(itemsRepo, mediaRepo, variantsRepo, pricesRepo, cfg)
            registerOfferRoutes(offersService)
            registerOrdersRoutes(orderDeps)
            registerBuyerDeliveryRoutes(deliveryService)
            registerWatchlistRoutes(itemsRepo, variantsRepo, watchlistRepo, cfg)
            registerLinkRoutes(linkResolveService, userActionRateLimiter)
            registerCartRoutes(cartService, cfg, userActionRateLimiter)
            registerAnalyticsRoutes(eventLogRepository, cfg)
        }
    }
}

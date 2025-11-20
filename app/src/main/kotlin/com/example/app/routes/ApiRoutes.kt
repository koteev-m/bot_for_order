package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.services.OffersService
import com.example.app.services.PaymentsService
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.app.security.installInitDataAuth
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.watchlist.WatchlistRepository
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
    val historyRepo by inject<OrderStatusHistoryRepository>()
    val paymentsService by inject<PaymentsService>()
    val offersService by inject<OffersService>()
    val holdService by inject<HoldService>()
    val lockManager by inject<LockManager>()
    val cfg by inject<AppConfig>()
    val watchlistRepo by inject<WatchlistRepository>()

    val orderDeps = OrderRoutesDeps(
        itemsRepository = itemsRepo,
        variantsRepository = variantsRepo,
        ordersRepository = ordersRepo,
        historyRepository = historyRepo,
        paymentsService = paymentsService,
        holdService = holdService,
        lockManager = lockManager
    )

    routing {
        route("/api") {
            installInitDataAuth(cfg)
            registerItemRoutes(itemsRepo, mediaRepo, variantsRepo, pricesRepo, cfg)
            registerOfferRoutes(offersService)
            registerOrdersRoutes(cfg, orderDeps)
            registerWatchlistRoutes(itemsRepo, variantsRepo, watchlistRepo, cfg)
        }
    }
}

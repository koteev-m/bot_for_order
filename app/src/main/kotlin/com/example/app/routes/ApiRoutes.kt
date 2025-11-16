package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.OrdersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.app.security.installInitDataAuth
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
    val cfg by inject<AppConfig>()

    routing {
        route("/api") {
            installInitDataAuth(cfg)
            registerItemRoutes(itemsRepo, mediaRepo, variantsRepo, pricesRepo)
            registerOfferRoutes(variantsRepo)
            registerOrdersRoutes(cfg, itemsRepo, variantsRepo, ordersRepo)
        }
    }
}

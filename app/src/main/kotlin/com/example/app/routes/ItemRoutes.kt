package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.DisplayPrices
import com.example.app.api.ItemMediaResponse
import com.example.app.api.ItemResponse
import com.example.app.api.VariantResponse
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.domain.PricesDisplay
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.registerItemRoutes(
    itemsRepo: ItemsRepository,
    mediaRepo: ItemMediaRepository,
    variantsRepo: VariantsRepository,
    pricesRepo: PricesDisplayRepository
) {
    get("/items/{id}") {
        handleGetItem(call, itemsRepo, mediaRepo, variantsRepo, pricesRepo)
    }
}

private suspend fun handleGetItem(
    call: ApplicationCall,
    itemsRepo: ItemsRepository,
    mediaRepo: ItemMediaRepository,
    variantsRepo: VariantsRepository,
    pricesRepo: PricesDisplayRepository
) {
    val id = call.parameters["id"] ?: throw ApiError("id required")
    val item = itemsRepo.getById(id) ?: throw ApiError("item not found", HttpStatusCode.NotFound)
    val prices: PricesDisplay? = pricesRepo.get(item.id)
    val media = mediaRepo.listByItem(item.id)
    val variants = variantsRepo.listByItem(item.id)

    val response = ItemResponse(
        id = item.id,
        title = item.title,
        description = item.description,
        status = item.status.name,
        allowBargain = item.allowBargain,
        prices = prices?.toDisplayPrices(),
        media = media.map { it.toResponse() },
        variants = variants.map { it.toResponse() }
    )
    call.respond(response)
}

private fun PricesDisplay.toDisplayPrices() = DisplayPrices(
    baseCurrency = baseCurrency,
    baseAmountMinor = baseAmountMinor,
    rub = displayRub,
    usd = displayUsd,
    eur = displayEur,
    usdtTs = displayUsdtTs
)

private fun com.example.domain.ItemMedia.toResponse() = ItemMediaResponse(
    fileId = fileId,
    mediaType = mediaType,
    sortOrder = sortOrder
)

private fun com.example.domain.Variant.toResponse() = VariantResponse(
    id = id,
    size = size,
    sku = sku,
    stock = stock,
    active = active
)

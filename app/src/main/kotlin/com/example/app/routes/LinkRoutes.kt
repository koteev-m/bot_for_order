package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.LinkItemSummary
import com.example.app.api.LinkResolveRequest
import com.example.app.api.LinkResolveResponse
import com.example.app.api.LinkSource
import com.example.app.security.requireUserId
import com.example.app.services.LinkContextService
import com.example.app.services.LinkResolveResult
import com.example.db.ItemsRepository
import com.example.db.VariantsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val linkLog = LoggerFactory.getLogger("LinkResolve")

fun Route.registerLinkRoutes(
    linkContextService: LinkContextService,
    itemsRepository: ItemsRepository,
    variantsRepository: VariantsRepository
) {
    post("/link/resolve") {
        handleLinkResolve(call, linkContextService, itemsRepository, variantsRepository)
    }
}

private suspend fun handleLinkResolve(
    call: ApplicationCall,
    linkContextService: LinkContextService,
    itemsRepository: ItemsRepository,
    variantsRepository: VariantsRepository
) {
    call.requireUserId()
    val req = call.receive<LinkResolveRequest>()
    val token = req.token.trim()
    if (token.isEmpty()) {
        throw ApiError("token_required", HttpStatusCode.BadRequest)
    }

    val resolved = linkContextService.resolve(token)
    when (resolved) {
        LinkResolveResult.NotFound -> throw ApiError("link_not_found", HttpStatusCode.NotFound)
        LinkResolveResult.Expired -> throw ApiError("link_expired", HttpStatusCode.Gone)
        LinkResolveResult.Revoked -> throw ApiError("link_revoked", HttpStatusCode.Gone)
        is LinkResolveResult.Found -> {
            val context = resolved.context
            val itemSummary = context.itemId?.let { itemId ->
                val item = itemsRepository.getById(itemId) ?: throw ApiError("item_not_found", HttpStatusCode.NotFound)
                LinkItemSummary(
                    itemId = item.id,
                    title = item.title,
                    description = item.description
                )
            }
            val requiresVariant = context.itemId?.let { itemId ->
                val activeVariants = variantsRepository.listByItem(itemId).count { it.active }
                activeVariants > 1
            } ?: false
            val response = LinkResolveResponse(
                action = context.action.name,
                button = context.button?.name,
                item = itemSummary,
                variantHint = context.variantHint,
                requiresVariant = requiresVariant,
                source = LinkSource(
                    merchantId = context.merchantId,
                    storefrontId = context.storefrontId,
                    channelId = context.channelId,
                    postId = context.postId
                ),
                legacy = resolved.legacy
            )
            linkLog.info(
                "webapp_opened token={} action={} item={} channel={} post={} legacy={}",
                tokenMask(token),
                context.action.name,
                context.itemId,
                context.channelId,
                context.postId,
                resolved.legacy
            )
            call.respond(response)
        }
    }
}

private fun tokenMask(token: String): String =
    token.take(6).padEnd(10, '*')

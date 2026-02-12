package com.example.app.routes

import com.example.app.api.ApiError
import com.example.app.api.LinkResolveRequest
import com.example.app.security.requireUserId
import com.example.app.services.LinkResolveException
import com.example.app.services.LinkResolveService
import com.example.app.services.QuickAddRequestValidation
import com.example.app.services.UserActionRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.registerLinkRoutes(
    linkResolveService: LinkResolveService,
    rateLimiter: UserActionRateLimiter
) {
    post("/link/resolve") {
        handleLinkResolve(call, linkResolveService, rateLimiter)
    }
}

private suspend fun handleLinkResolve(
    call: ApplicationCall,
    linkResolveService: LinkResolveService,
    rateLimiter: UserActionRateLimiter
) {
    val request = call.receiveNullable<LinkResolveRequest>()
        ?: throw ApiError("invalid_request", HttpStatusCode.BadRequest)
    val token = QuickAddRequestValidation.normalizeToken(request.token)
    val userId = call.requireUserId()
    if (!rateLimiter.allowResolve(userId)) {
        throw ApiError("rate_limited", HttpStatusCode.TooManyRequests)
    }
    val response = try {
        linkResolveService.resolve(token)
    } catch (e: LinkResolveException) {
        val code = e.message ?: "not_found"
        throw ApiError(code, HttpStatusCode.NotFound)
    }
    call.respond(response)
}

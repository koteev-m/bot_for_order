package com.example.app

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() = EngineMain.main(emptyArray())

@Suppress("unused")
fun Application.module() {
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText("Internal error", status = HttpStatusCode.InternalServerError)
            this@module.environment.log.error("Unhandled", cause)
        }
    }
    install(ContentNegotiation) { json() }

    routing {
        get("/health") { call.respondText("OK") }
        get("/") { call.respondText("tg-shop-monorepo up") }
    }
}

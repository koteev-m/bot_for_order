package com.example.app.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/**
 * Вебхуки Telegram — принимаем сырой JSON. Логи — только факт получения и тип апдейта, без персональных данных.
 * Бизнес-логика/команды — в S7–S10.
 */
fun Application.installWebhookRoutes() {
    val log = LoggerFactory.getLogger("Webhook")

    routing {
        post("/tg/admin") {
            val body = call.receiveText()
            log.info("admin webhook: {} bytes", body.length)
            call.respond(HttpStatusCode.OK)
        }
        post("/tg/shop") {
            val body = call.receiveText()
            log.info("shop webhook: {} bytes", body.length)
            call.respond(HttpStatusCode.OK)
        }
    }
}

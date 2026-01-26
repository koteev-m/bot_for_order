package com.example.app.routes

import com.example.app.api.AdminPaymentDetailsRequest
import com.example.app.api.AdminPaymentRejectRequest
import com.example.app.api.AttachmentUrlResponse
import com.example.app.api.PaymentSelectResponse
import com.example.app.config.AppConfig
import com.example.app.security.installInitDataAuth
import com.example.app.security.requireAdminId
import com.example.app.security.TelegramInitDataVerifier
import com.example.app.services.ManualPaymentsService
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Duration
import org.koin.ktor.ext.inject

fun Application.installAdminApiRoutes() {
    val manualPaymentsService by inject<ManualPaymentsService>()
    val cfg by inject<AppConfig>()
    val initDataVerifier by inject<TelegramInitDataVerifier>()

    routing {
        route("/api/admin") {
            installInitDataAuth(initDataVerifier)
            post("/orders/{id}/payment/details") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val adminId = call.requireAdminId(cfg)
                val req = call.receive<AdminPaymentDetailsRequest>()
                val order = manualPaymentsService.setPaymentDetails(orderId, adminId, req.text)
                call.respond(PaymentSelectResponse(orderId = order.id, status = order.status.name))
            }
            post("/orders/{id}/payment/confirm") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val adminId = call.requireAdminId(cfg)
                val order = manualPaymentsService.confirmPayment(orderId, adminId)
                call.respond(PaymentSelectResponse(orderId = order.id, status = order.status.name))
            }
            post("/orders/{id}/payment/reject") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val adminId = call.requireAdminId(cfg)
                val req = call.receive<AdminPaymentRejectRequest>()
                val order = manualPaymentsService.rejectPayment(orderId, adminId, req.reason)
                call.respond(PaymentSelectResponse(orderId = order.id, status = order.status.name))
            }
            get("/orders/{id}/attachments/{attachmentId}/url") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                call.requireAdminId(cfg)
                val attachmentId = call.parameters["attachmentId"]?.toLongOrNull()
                    ?: throw IllegalArgumentException("attachment id missing")
                val ttl = Duration.ofSeconds(cfg.storage.presignTtlSeconds)
                val url = manualPaymentsService.presignAttachmentUrl(orderId, attachmentId, ttl)
                call.respond(AttachmentUrlResponse(url = url))
            }
        }
    }
}

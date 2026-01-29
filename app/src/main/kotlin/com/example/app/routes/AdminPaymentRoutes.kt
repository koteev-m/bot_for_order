package com.example.app.routes

import com.example.app.api.AdminChannelBindingDto
import com.example.app.api.AdminChannelBindingRequest
import com.example.app.api.AdminDeliveryMethodDto
import com.example.app.api.AdminDeliveryMethodUpdateRequest
import com.example.app.api.AdminMeResponse
import com.example.app.api.AdminOrderCardResponse
import com.example.app.api.AdminOrderStatusRequest
import com.example.app.api.AdminOrderSummary
import com.example.app.api.AdminOrdersPage
import com.example.app.api.AdminPaymentAttachment
import com.example.app.api.AdminPaymentClaim
import com.example.app.api.AdminPaymentDetailsRequest
import com.example.app.api.AdminPaymentInfo
import com.example.app.api.AdminPaymentMethodDto
import com.example.app.api.AdminPaymentMethodUpdate
import com.example.app.api.AdminPaymentMethodsUpdateRequest
import com.example.app.api.AdminPaymentRejectRequest
import com.example.app.api.AdminPublishRequest
import com.example.app.api.AdminPublishResponse
import com.example.app.api.AdminPublishResult
import com.example.app.api.AdminStorefrontDto
import com.example.app.api.AdminStorefrontRequest
import com.example.app.api.AttachmentUrlResponse
import com.example.app.api.OrderLineDto
import com.example.app.api.OrderDeliverySummary
import com.example.app.api.PaymentSelectResponse
import com.example.app.api.SimpleResponse
import com.example.app.api.ApiError
import com.example.app.config.AppConfig
import com.example.app.security.TelegramInitDataVerifier
import com.example.app.security.installInitDataAuth
import com.example.app.security.requireAdminUser
import com.example.app.services.DeliveryFieldsCodec
import com.example.app.services.IdempotencyService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.OrderStatusService
import com.example.app.services.PaymentDetailsCrypto
import com.example.app.util.ClientIpResolver
import com.example.db.AuditLogRepository
import com.example.db.AdminUsersRepository
import com.example.db.ChannelBindingsRepository
import com.example.db.MerchantDeliveryMethodsRepository
import com.example.db.MerchantPaymentMethodsRepository
import com.example.db.OrderAttachmentsRepository
import com.example.db.OrderDeliveryRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderPaymentClaimsRepository
import com.example.db.OrdersRepository
import com.example.db.StorefrontsRepository
import com.example.domain.AdminRole
import com.example.domain.Order
import com.example.domain.DeliveryMethodType
import com.example.domain.MerchantDeliveryMethod
import com.example.domain.MerchantPaymentMethod
import com.example.domain.OrderAttachmentKind
import com.example.domain.OrderStatus
import com.example.domain.PaymentMethodMode
import com.example.domain.PaymentMethodType
import com.example.domain.Storefront
import com.example.app.services.PostService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.koin.ktor.ext.inject

fun Application.installAdminApiRoutes() {
    val manualPaymentsService by inject<ManualPaymentsService>()
    val orderStatusService by inject<OrderStatusService>()
    val cfg by inject<AppConfig>()
    val initDataVerifier by inject<TelegramInitDataVerifier>()
    val adminUsersRepository by inject<AdminUsersRepository>()
    val ordersRepository by inject<OrdersRepository>()
    val orderLinesRepository by inject<OrderLinesRepository>()
    val orderDeliveryRepository by inject<OrderDeliveryRepository>()
    val paymentClaimsRepository by inject<OrderPaymentClaimsRepository>()
    val attachmentsRepository by inject<OrderAttachmentsRepository>()
    val paymentMethodsRepository by inject<MerchantPaymentMethodsRepository>()
    val deliveryMethodsRepository by inject<MerchantDeliveryMethodsRepository>()
    val storefrontsRepository by inject<StorefrontsRepository>()
    val channelBindingsRepository by inject<ChannelBindingsRepository>()
    val paymentDetailsCrypto by inject<PaymentDetailsCrypto>()
    val postService by inject<PostService>()
    val auditLogRepository by inject<AuditLogRepository>()
    val idempotencyService by inject<IdempotencyService>()

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val idempotencyJson = Json { explicitNulls = false; encodeDefaults = false }

    routing {
        route("/api/admin") {
            installInitDataAuth(initDataVerifier)

            get("/me") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                call.respond(
                    AdminMeResponse(
                        userId = admin.userId,
                        role = admin.role.name,
                        merchantId = admin.merchantId
                    )
                )
            }

            get("/orders") {
                call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val bucket = call.request.queryParameters["bucket"] ?: "awaiting_payment"
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
                val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val statuses = resolveBucketStatuses(bucket)
                val merchantId = cfg.merchants.defaultMerchantId
                val orders = ordersRepository.listByMerchantAndStatus(merchantId, statuses, limit, offset)
                val deliveries = orderDeliveryRepository.listByOrders(orders.map { it.id })
                val items = orders.map { order ->
                    val delivery = deliveries[order.id]?.let { stored ->
                        OrderDeliverySummary(
                            type = stored.type.name,
                            fields = DeliveryFieldsCodec.decodeFields(stored.fieldsJson)
                        )
                    }
                    AdminOrderSummary(
                        orderId = order.id,
                        status = order.status.name,
                        amountMinor = order.amountMinor,
                        currency = order.currency,
                        updatedAt = order.updatedAt.toString(),
                        buyerId = order.userId,
                        paymentMethodType = order.paymentMethodType?.name,
                        delivery = delivery
                    )
                }
                call.respond(AdminOrdersPage(items = items))
            }

            get("/orders/{id}") {
                call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
                if (order.merchantId != cfg.merchants.defaultMerchantId) {
                    throw ApiError("order_not_found", HttpStatusCode.NotFound)
                }
                val lines = orderLinesRepository.listByOrder(orderId)
                val delivery = orderDeliveryRepository.getByOrder(orderId)?.let { stored ->
                    OrderDeliverySummary(
                        type = stored.type.name,
                        fields = DeliveryFieldsCodec.decodeFields(stored.fieldsJson)
                    )
                }
                val claim = paymentClaimsRepository.getLatestByOrder(orderId)
                val attachments = if (claim != null) {
                    val ttl = Duration.ofMinutes(10)
                    attachmentsRepository.listByClaimAndKind(claim.id, OrderAttachmentKind.PAYMENT_PROOF)
                        .map { attachment ->
                            AdminPaymentAttachment(
                                id = attachment.id,
                                presignedUrl = manualPaymentsService.presignAttachmentUrl(orderId, attachment.id, ttl),
                                mime = attachment.mime,
                                size = attachment.size
                            )
                        }
                } else {
                    emptyList()
                }
                val paymentInfo = AdminPaymentInfo(
                    methodType = order.paymentMethodType?.name,
                    claim = claim?.let {
                        AdminPaymentClaim(
                            id = it.id,
                            txid = it.txid,
                            comment = it.comment,
                            status = it.status.name,
                            createdAt = it.createdAt.toString()
                        )
                    },
                    attachments = attachments
                )
                call.respond(
                    AdminOrderCardResponse(
                        orderId = order.id,
                        status = order.status.name,
                        amountMinor = order.amountMinor,
                        currency = order.currency,
                        buyerId = order.userId,
                        itemId = order.itemId,
                        variantId = order.variantId,
                        qty = order.qty,
                        createdAt = order.createdAt.toString(),
                        updatedAt = order.updatedAt.toString(),
                        lines = lines.map { line ->
                            OrderLineDto(
                                listingId = line.listingId,
                                variantId = line.variantId,
                                qty = line.qty,
                                priceSnapshotMinor = line.priceSnapshotMinor,
                                currency = line.currency,
                                sourceStorefrontId = line.sourceStorefrontId,
                                sourceChannelId = line.sourceChannelId,
                                sourcePostMessageId = line.sourcePostMessageId
                            )
                        },
                        delivery = delivery,
                        payment = paymentInfo
                    )
                )
            }

            post("/orders/{id}/payment/details") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                ensureOrderForMerchant(orderId, ordersRepository, cfg.merchants.defaultMerchantId)
                val req = call.receive<AdminPaymentDetailsRequest>()
                val order = manualPaymentsService.setPaymentDetails(orderId, admin.userId, req.text)
                val payload = buildJsonObject {
                    put("details_present", true)
                    put("details_hash", sha256Hex(req.text.trim()))
                }
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_payment_details_set",
                    orderId = orderId,
                    payloadJson = idempotencyJson.encodeToString(payload)
                )
                call.respond(PaymentSelectResponse(orderId = order.id, status = order.status.name))
            }
            post("/orders/{id}/payment/confirm") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                ensureOrderForMerchant(orderId, ordersRepository, cfg.merchants.defaultMerchantId)
                val idempotencyKey = idempotencyService.normalizeKey(call.request.headers["Idempotency-Key"])
                if (idempotencyKey == null) {
                    val order = manualPaymentsService.confirmPayment(orderId, admin.userId)
                    writeAuditLog(
                        auditLogRepository = auditLogRepository,
                        cfg = cfg,
                        call = call,
                        adminUserId = admin.userId,
                        action = "admin_payment_confirm",
                        orderId = orderId,
                        payloadJson = idempotencyJson.encodeToString(buildJsonObject { put("status", order.status.name) })
                    )
                    call.respond(PaymentSelectResponse(orderId = order.id, status = order.status.name))
                    return@post
                }

                val requestHash = idempotencyService.hashPayload(
                    idempotencyJson.encodeToString(buildJsonObject { put("orderId", orderId) })
                )
                val outcome = idempotencyService.execute(
                    merchantId = admin.merchantId,
                    userId = admin.userId,
                    scope = IDEMPOTENCY_SCOPE_ADMIN_CONFIRM_PAYMENT,
                    key = idempotencyKey,
                    requestHash = requestHash
                ) {
                    val order = manualPaymentsService.confirmPayment(orderId, admin.userId)
                    writeAuditLog(
                        auditLogRepository = auditLogRepository,
                        cfg = cfg,
                        call = call,
                        adminUserId = admin.userId,
                        action = "admin_payment_confirm",
                        orderId = orderId,
                        payloadJson = idempotencyJson.encodeToString(buildJsonObject { put("status", order.status.name) })
                    )
                    val response = PaymentSelectResponse(orderId = order.id, status = order.status.name)
                    IdempotencyService.IdempotentResponse(
                        status = HttpStatusCode.OK,
                        response = response,
                        responseJson = idempotencyJson.encodeToString(response)
                    )
                }
                when (outcome) {
                    is IdempotencyService.IdempotentOutcome.Replay -> call.respondText(
                        outcome.responseJson,
                        io.ktor.http.ContentType.Application.Json,
                        outcome.status
                    )
                    is IdempotencyService.IdempotentOutcome.Executed -> call.respond(outcome.status, outcome.response)
                }
            }
            post("/orders/{id}/payment/reject") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                ensureOrderForMerchant(orderId, ordersRepository, cfg.merchants.defaultMerchantId)
                val req = call.receive<AdminPaymentRejectRequest>()
                val order = manualPaymentsService.rejectPayment(orderId, admin.userId, req.reason)
                val payload = buildJsonObject {
                    put("reason_present", req.reason.isNotBlank())
                    put("reason_hash", sha256Hex(req.reason.trim()))
                }
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_payment_reject",
                    orderId = orderId,
                    payloadJson = idempotencyJson.encodeToString(payload)
                )
                call.respond(PaymentSelectResponse(orderId = order.id, status = order.status.name))
            }
            post("/orders/{id}/status") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val req = call.receive<AdminOrderStatusRequest>()
                ensureOrderForMerchant(orderId, ordersRepository, cfg.merchants.defaultMerchantId)
                val status = runCatching { OrderStatus.valueOf(req.status) }.getOrElse {
                    throw ApiError("invalid_status", HttpStatusCode.BadRequest)
                }
                val comment = buildStatusComment(status, req.comment, req.trackingCode)
                val idempotencyKey = idempotencyService.normalizeKey(call.request.headers["Idempotency-Key"])
                if (idempotencyKey == null) {
                    val result = orderStatusService.changeStatus(orderId, status, admin.userId, comment)
                    if (result.changed) {
                        val payload = buildJsonObject {
                            put("status", status.name)
                            put("comment_present", !comment.isNullOrBlank())
                            put("comment_hash", comment?.let { sha256Hex(it) }.orEmpty())
                        }
                        writeAuditLog(
                            auditLogRepository = auditLogRepository,
                            cfg = cfg,
                            call = call,
                            adminUserId = admin.userId,
                            action = "admin_status_change",
                            orderId = orderId,
                            payloadJson = idempotencyJson.encodeToString(payload)
                        )
                    }
                    call.respond(PaymentSelectResponse(orderId = result.order.id, status = result.order.status.name))
                    return@post
                }

                val requestHash = idempotencyService.hashPayload(
                    idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("orderId", orderId)
                            put("status", status.name)
                            put("comment", comment.orEmpty())
                        }
                    )
                )
                val outcome = idempotencyService.execute(
                    merchantId = admin.merchantId,
                    userId = admin.userId,
                    scope = IDEMPOTENCY_SCOPE_ADMIN_STATUS_CHANGE,
                    key = idempotencyKey,
                    requestHash = requestHash
                ) {
                    val result = orderStatusService.changeStatus(orderId, status, admin.userId, comment)
                    if (result.changed) {
                        val payload = buildJsonObject {
                            put("status", status.name)
                            put("comment_present", !comment.isNullOrBlank())
                            put("comment_hash", comment?.let { sha256Hex(it) }.orEmpty())
                        }
                        writeAuditLog(
                            auditLogRepository = auditLogRepository,
                            cfg = cfg,
                            call = call,
                            adminUserId = admin.userId,
                            action = "admin_status_change",
                            orderId = orderId,
                            payloadJson = idempotencyJson.encodeToString(payload)
                        )
                    }
                    val response = PaymentSelectResponse(orderId = result.order.id, status = result.order.status.name)
                    IdempotencyService.IdempotentResponse(
                        status = HttpStatusCode.OK,
                        response = response,
                        responseJson = idempotencyJson.encodeToString(response)
                    )
                }
                when (outcome) {
                    is IdempotencyService.IdempotentOutcome.Replay -> call.respondText(
                        outcome.responseJson,
                        io.ktor.http.ContentType.Application.Json,
                        outcome.status
                    )
                    is IdempotencyService.IdempotentOutcome.Executed -> call.respond(outcome.status, outcome.response)
                }
            }
            get("/orders/{id}/attachments/{attachmentId}/url") {
                val orderId = call.parameters["id"] ?: throw IllegalArgumentException("order id missing")
                call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val attachmentId = call.parameters["attachmentId"]?.toLongOrNull()
                    ?: throw IllegalArgumentException("attachment id missing")
                ensureOrderForMerchant(orderId, ordersRepository, cfg.merchants.defaultMerchantId)
                val ttl = Duration.ofSeconds(cfg.storage.presignTtlSeconds)
                val url = manualPaymentsService.presignAttachmentUrl(orderId, attachmentId, ttl)
                call.respond(AttachmentUrlResponse(url = url))
            }

            get("/settings/payment_methods") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val merchantId = cfg.merchants.defaultMerchantId
                val methods = PaymentMethodType.values().map { type ->
                    val stored = paymentMethodsRepository.getMethod(merchantId, type)
                    val mode = stored?.mode ?: PaymentMethodMode.MANUAL_SEND
                    val enabled = stored?.enabled ?: false
                    val details = if (admin.role == AdminRole.OWNER) {
                        stored?.detailsEncrypted?.let { payload ->
                            runCatching { paymentDetailsCrypto.decrypt(payload) }.getOrNull()
                        }
                    } else {
                        null
                    }
                    AdminPaymentMethodDto(
                        type = type.name,
                        mode = mode.name,
                        enabled = enabled,
                        details = details
                    )
                }
                call.respond(methods)
            }
            put("/settings/payment_methods") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val merchantId = cfg.merchants.defaultMerchantId
                val req = call.receive<AdminPaymentMethodsUpdateRequest>()
                req.methods.forEach { update ->
                    val resolved = resolvePaymentMethodUpdate(merchantId, update, paymentDetailsCrypto)
                    paymentMethodsRepository.upsert(resolved)
                }
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_payment_methods_update",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(buildPaymentMethodsPayload(req))
                )
                call.respond(SimpleResponse())
            }
            post("/settings/payment_methods") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val merchantId = cfg.merchants.defaultMerchantId
                val req = call.receive<AdminPaymentMethodsUpdateRequest>()
                req.methods.forEach { update ->
                    val resolved = resolvePaymentMethodUpdate(merchantId, update, paymentDetailsCrypto)
                    paymentMethodsRepository.upsert(resolved)
                }
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_payment_methods_update",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(buildPaymentMethodsPayload(req))
                )
                call.respond(SimpleResponse())
            }

            get("/settings/delivery_method") {
                call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val merchantId = cfg.merchants.defaultMerchantId
                val stored = deliveryMethodsRepository.getMethod(merchantId, DeliveryMethodType.CDEK_PICKUP_MANUAL)
                val required = stored?.requiredFieldsJson?.let(DeliveryFieldsCodec::parseRequiredFields).orEmpty()
                val response = AdminDeliveryMethodDto(
                    type = DeliveryMethodType.CDEK_PICKUP_MANUAL.name,
                    enabled = stored?.enabled ?: false,
                    requiredFields = required
                )
                call.respond(response)
            }
            put("/settings/delivery_method") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val merchantId = cfg.merchants.defaultMerchantId
                val req = call.receive<AdminDeliveryMethodUpdateRequest>()
                val normalized = req.requiredFields.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                validateRequiredFields(normalized)
                val method = MerchantDeliveryMethod(
                    merchantId = merchantId,
                    type = DeliveryMethodType.CDEK_PICKUP_MANUAL,
                    enabled = req.enabled,
                    requiredFieldsJson = json.encodeToString(normalized)
                )
                deliveryMethodsRepository.upsert(method)
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_delivery_method_update",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("enabled", req.enabled)
                            put("required_fields", buildJsonArray { normalized.forEach { add(JsonPrimitive(it)) } })
                        }
                    )
                )
                call.respond(SimpleResponse())
            }
            post("/settings/delivery_method") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val merchantId = cfg.merchants.defaultMerchantId
                val req = call.receive<AdminDeliveryMethodUpdateRequest>()
                val normalized = req.requiredFields.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                validateRequiredFields(normalized)
                val method = MerchantDeliveryMethod(
                    merchantId = merchantId,
                    type = DeliveryMethodType.CDEK_PICKUP_MANUAL,
                    enabled = req.enabled,
                    requiredFieldsJson = json.encodeToString(normalized)
                )
                deliveryMethodsRepository.upsert(method)
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_delivery_method_update",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("enabled", req.enabled)
                            put("required_fields", buildJsonArray { normalized.forEach { add(JsonPrimitive(it)) } })
                        }
                    )
                )
                call.respond(SimpleResponse())
            }

            get("/settings/storefronts") {
                call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val merchantId = cfg.merchants.defaultMerchantId
                val storefronts = storefrontsRepository.listByMerchant(merchantId).map {
                    AdminStorefrontDto(id = it.id, name = it.name)
                }
                call.respond(storefronts)
            }
            put("/settings/storefronts") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val merchantId = cfg.merchants.defaultMerchantId
                val req = call.receive<AdminStorefrontRequest>()
                val existing = storefrontsRepository.getById(req.id)
                if (existing != null && existing.merchantId != merchantId) {
                    throw ApiError("storefront_not_found", HttpStatusCode.NotFound)
                }
                val storefront = Storefront(id = req.id, merchantId = merchantId, name = req.name)
                storefrontsRepository.upsert(storefront)
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_storefront_upsert",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("storefront_id", req.id)
                            put("name", req.name)
                        }
                    )
                )
                call.respond(AdminStorefrontDto(id = storefront.id, name = storefront.name))
            }
            post("/settings/storefronts") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val merchantId = cfg.merchants.defaultMerchantId
                val req = call.receive<AdminStorefrontRequest>()
                val existing = storefrontsRepository.getById(req.id)
                if (existing != null && existing.merchantId != merchantId) {
                    throw ApiError("storefront_not_found", HttpStatusCode.NotFound)
                }
                val storefront = Storefront(id = req.id, merchantId = merchantId, name = req.name)
                storefrontsRepository.upsert(storefront)
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_storefront_upsert",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("storefront_id", req.id)
                            put("name", req.name)
                        }
                    )
                )
                call.respond(AdminStorefrontDto(id = storefront.id, name = storefront.name))
            }

            get("/settings/channel_bindings") {
                call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OPERATOR))
                val merchantId = cfg.merchants.defaultMerchantId
                val storefronts = storefrontsRepository.listByMerchant(merchantId)
                val bindings = storefronts.flatMap { storefront ->
                    channelBindingsRepository.listByStorefront(storefront.id).map { binding ->
                        AdminChannelBindingDto(
                            id = binding.id,
                            storefrontId = binding.storefrontId,
                            channelId = binding.channelId
                        )
                    }
                }
                call.respond(bindings)
            }
            put("/settings/channel_bindings") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val req = call.receive<AdminChannelBindingRequest>()
                ensureStorefrontForMerchant(req.storefrontId, storefrontsRepository, cfg.merchants.defaultMerchantId)
                ensureChannelForMerchant(
                    req.channelId,
                    channelBindingsRepository,
                    storefrontsRepository,
                    cfg.merchants.defaultMerchantId
                )
                val id = channelBindingsRepository.upsert(req.storefrontId, req.channelId, Instant.now())
                call.respond(
                    AdminChannelBindingDto(
                        id = id,
                        storefrontId = req.storefrontId,
                        channelId = req.channelId
                    )
                )
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_channel_binding_upsert",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("storefront_id", req.storefrontId)
                            put("channel_id", req.channelId)
                        }
                    )
                )
            }
            post("/settings/channel_bindings") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val req = call.receive<AdminChannelBindingRequest>()
                ensureStorefrontForMerchant(req.storefrontId, storefrontsRepository, cfg.merchants.defaultMerchantId)
                ensureChannelForMerchant(
                    req.channelId,
                    channelBindingsRepository,
                    storefrontsRepository,
                    cfg.merchants.defaultMerchantId
                )
                val id = channelBindingsRepository.upsert(req.storefrontId, req.channelId, Instant.now())
                call.respond(
                    AdminChannelBindingDto(
                        id = id,
                        storefrontId = req.storefrontId,
                        channelId = req.channelId
                    )
                )
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_channel_binding_upsert",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("storefront_id", req.storefrontId)
                            put("channel_id", req.channelId)
                        }
                    )
                )
            }

            post("/publications/publish") {
                val admin = call.requireAdminUser(cfg, adminUsersRepository, setOf(AdminRole.OWNER))
                val req = call.receive<AdminPublishRequest>()
                val results = postService.postItemAlbumToChannels(req.itemId, req.channelIds).map { result ->
                    AdminPublishResult(
                        channelId = result.channelId,
                        ok = result.ok,
                        error = result.error
                    )
                }
                writeAuditLog(
                    auditLogRepository = auditLogRepository,
                    cfg = cfg,
                    call = call,
                    adminUserId = admin.userId,
                    action = "admin_publish",
                    orderId = null,
                    payloadJson = idempotencyJson.encodeToString(
                        buildJsonObject {
                            put("item_id", req.itemId)
                            put("channel_ids", buildJsonArray { req.channelIds.forEach { add(JsonPrimitive(it)) } })
                        }
                    )
                )
                call.respond(AdminPublishResponse(results = results))
            }
        }
    }
}

private fun resolveBucketStatuses(bucket: String): List<OrderStatus> = when (bucket) {
    "awaiting_payment" -> listOf(
        OrderStatus.AWAITING_PAYMENT_DETAILS,
        OrderStatus.AWAITING_PAYMENT,
        OrderStatus.pending
    )
    "under_review" -> listOf(OrderStatus.PAYMENT_UNDER_REVIEW)
    "paid" -> listOf(OrderStatus.paid, OrderStatus.PAID_CONFIRMED, OrderStatus.fulfillment)
    "shipped" -> listOf(OrderStatus.shipped)
    else -> listOf(OrderStatus.AWAITING_PAYMENT_DETAILS, OrderStatus.AWAITING_PAYMENT)
}

private fun buildStatusComment(status: OrderStatus, comment: String?, trackingCode: String?): String? {
    val normalizedTracking = trackingCode?.trim()?.takeIf { it.isNotBlank() }
    val normalizedComment = comment?.trim()?.takeIf { it.isNotBlank() }
    if (status != OrderStatus.shipped) {
        return normalizedComment
    }
    return when {
        normalizedTracking != null && normalizedComment != null ->
            "${normalizedComment} tracking:$normalizedTracking"
        normalizedTracking != null -> "tracking:$normalizedTracking"
        else -> normalizedComment
    }
}

private fun resolvePaymentMethodUpdate(
    merchantId: String,
    update: AdminPaymentMethodUpdate,
    crypto: PaymentDetailsCrypto
): MerchantPaymentMethod {
    val type = runCatching { PaymentMethodType.valueOf(update.type) }.getOrElse {
        throw ApiError("invalid_payment_method", HttpStatusCode.BadRequest)
    }
    val mode = runCatching { PaymentMethodMode.valueOf(update.mode) }.getOrElse {
        throw ApiError("invalid_payment_mode", HttpStatusCode.BadRequest)
    }
    val details = update.details?.trim()?.takeIf { it.isNotEmpty() }
    val encrypted = if (mode == PaymentMethodMode.AUTO) {
        if (details == null) {
            throw ApiError("payment_details_required", HttpStatusCode.BadRequest)
        }
        crypto.encrypt(details)
    } else {
        null
    }
    return MerchantPaymentMethod(
        merchantId = merchantId,
        type = type,
        mode = mode,
        detailsEncrypted = encrypted,
        enabled = update.enabled
    )
}

private suspend fun ensureOrderForMerchant(
    orderId: String,
    ordersRepository: OrdersRepository,
    merchantId: String
): Order {
    val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
    if (order.merchantId != merchantId) {
        throw ApiError("order_not_found", HttpStatusCode.NotFound)
    }
    return order
}

private suspend fun ensureStorefrontForMerchant(
    storefrontId: String,
    storefrontsRepository: StorefrontsRepository,
    merchantId: String
) {
    val storefront = storefrontsRepository.getById(storefrontId)
        ?: throw ApiError("storefront_not_found", HttpStatusCode.NotFound)
    if (storefront.merchantId != merchantId) {
        throw ApiError("forbidden", HttpStatusCode.Forbidden)
    }
}

private suspend fun ensureChannelForMerchant(
    channelId: Long,
    channelBindingsRepository: ChannelBindingsRepository,
    storefrontsRepository: StorefrontsRepository,
    merchantId: String
) {
    val existing = channelBindingsRepository.getByChannel(channelId) ?: return
    val storefront = storefrontsRepository.getById(existing.storefrontId)
        ?: throw ApiError("forbidden", HttpStatusCode.Forbidden)
    if (storefront.merchantId != merchantId) {
        throw ApiError("forbidden", HttpStatusCode.Forbidden)
    }
}

private fun validateRequiredFields(requiredFields: List<String>) {
    if (requiredFields.size > MAX_REQUIRED_FIELDS) {
        throw ApiError("invalid_required_fields", HttpStatusCode.BadRequest)
    }
    requiredFields.forEach { field ->
        val trimmed = field.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_REQUIRED_FIELD_LENGTH) {
            throw ApiError("invalid_required_fields", HttpStatusCode.BadRequest)
        }
    }
}

private suspend fun writeAuditLog(
    auditLogRepository: AuditLogRepository,
    cfg: AppConfig,
    call: io.ktor.server.application.ApplicationCall,
    adminUserId: Long,
    action: String,
    orderId: String?,
    payloadJson: String
) {
    val ip = ClientIpResolver.resolve(call, cfg.metrics.trustedProxyAllowlist)
    val userAgent = call.request.headers["User-Agent"]
    auditLogRepository.insert(
        com.example.domain.AuditLogEntry(
            adminUserId = adminUserId,
            action = action,
            orderId = orderId,
            payloadJson = payloadJson,
            createdAt = Instant.now(),
            ip = ip,
            userAgent = userAgent
        )
    )
}

private fun buildPaymentMethodsPayload(req: AdminPaymentMethodsUpdateRequest) = buildJsonObject {
    put(
        "methods",
        buildJsonArray {
            req.methods.forEach { update ->
                add(
                    buildJsonObject {
                        put("type", update.type)
                        put("mode", update.mode)
                        put("enabled", update.enabled)
                        put("details_present", !update.details.isNullOrBlank())
                    }
                )
            }
        }
    )
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private const val MAX_REQUIRED_FIELDS = 20
private const val MAX_REQUIRED_FIELD_LENGTH = 100
private const val IDEMPOTENCY_SCOPE_ADMIN_CONFIRM_PAYMENT = "admin_confirm_payment"
private const val IDEMPOTENCY_SCOPE_ADMIN_STATUS_CHANGE = "admin_status_change"

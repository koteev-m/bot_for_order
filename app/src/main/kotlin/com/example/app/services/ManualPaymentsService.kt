package com.example.app.services

import com.example.app.api.ApiError
import com.example.app.storage.Storage
import com.example.db.DatabaseTx
import com.example.db.MerchantPaymentMethodsRepository
import com.example.db.MerchantsRepository
import com.example.db.OrderAttachmentsRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderPaymentClaimsRepository
import com.example.db.OrderPaymentDetailsRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.VariantsRepository
import com.example.db.EventLogRepository
import com.example.domain.MerchantPaymentMethod
import com.example.domain.Order
import com.example.domain.OrderAttachment
import com.example.domain.OrderAttachmentKind
import com.example.domain.OrderLine
import com.example.domain.OrderPaymentClaim
import com.example.domain.OrderPaymentDetails
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.EventLogEntry
import com.example.domain.PaymentClaimStatus
import com.example.domain.PaymentMethodMode
import com.example.domain.PaymentMethodType
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldRequest
import com.example.domain.hold.OrderHoldService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory

data class PaymentInstructions(
    val methodType: PaymentMethodType,
    val mode: PaymentMethodMode,
    val text: String
)

data class PaymentClaimAttachment(
    val filename: String?,
    val contentType: String,
    val bytes: ByteArray
)

private data class NormalizedClaimInput(
    val txid: String?,
    val comment: String?
)

class ManualPaymentsService(
    private val dbTx: DatabaseTx,
    private val ordersRepository: OrdersRepository,
    private val orderLinesRepository: OrderLinesRepository,
    private val orderStatusHistoryRepository: OrderStatusHistoryRepository,
    private val merchantsRepository: MerchantsRepository,
    private val paymentMethodsRepository: MerchantPaymentMethodsRepository,
    private val paymentDetailsRepository: OrderPaymentDetailsRepository,
    private val paymentClaimsRepository: OrderPaymentClaimsRepository,
    private val attachmentsRepository: OrderAttachmentsRepository,
    private val variantsRepository: VariantsRepository,
    private val orderHoldService: OrderHoldService,
    private val holdService: HoldService,
    private val lockManager: LockManager,
    private val eventLogRepository: EventLogRepository,
    private val storage: Storage,
    private val crypto: PaymentDetailsCrypto,
    private val notifier: ManualPaymentsNotifier,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(ManualPaymentsService::class.java)

    suspend fun selectPaymentMethod(orderId: String, buyerId: Long, type: PaymentMethodType): Order {
        return lockManager.withLock("order:$orderId:payment_select", LOCK_WAIT_MS, LOCK_LEASE_MS) {
            val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
            ensureOwner(order, buyerId)
            if (order.paymentMethodType != null && order.paymentMethodType == type) {
                return@withLock order
            }
            if (!SELECT_ALLOWED_STATUSES.contains(order.status)) {
                throw ApiError("payment_selection_not_allowed", HttpStatusCode.Conflict)
            }
            val method = paymentMethodsRepository.getEnabledMethod(order.merchantId, type)
                ?: throw ApiError("payment_method_unavailable", HttpStatusCode.Conflict)

            if (order.paymentMethodType != null && order.paymentMethodType != type) {
                throw ApiError("payment_method_already_selected", HttpStatusCode.Conflict)
            }

            val now = Instant.now(clock)
            val selected = ordersRepository.setPaymentMethodSelection(orderId, type, now)
            val updatedOrder = if (!selected) {
                ordersRepository.get(orderId) ?: order
            } else {
                ordersRepository.get(orderId) ?: order.copy(paymentMethodType = type, paymentMethodSelectedAt = now)
            }
            val targetStatus = resolveAwaitingStatus(orderId, method)
            val finalOrder = if (updatedOrder.status != targetStatus) {
                OrderStatusTransitions.requireAllowed(updatedOrder.status, targetStatus)
                ordersRepository.setStatus(orderId, targetStatus)
                appendHistory(orderId, targetStatus, "payment_method_selected", buyerId)
                logStatusChange(updatedOrder, targetStatus)
                ordersRepository.get(orderId) ?: updatedOrder.copy(status = targetStatus)
            } else {
                updatedOrder
            }
            finalOrder
        }
    }

    suspend fun getPaymentInstructions(orderId: String, buyerId: Long): PaymentInstructions {
        val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
        ensureOwner(order, buyerId)
        val methodType = order.paymentMethodType
            ?: throw ApiError("payment_method_not_selected", HttpStatusCode.Conflict)
        val method = paymentMethodsRepository.getEnabledMethod(order.merchantId, methodType)
            ?: throw ApiError("payment_method_unavailable", HttpStatusCode.Conflict)
        val text = when (method.mode) {
            PaymentMethodMode.AUTO -> {
                val payload = method.detailsEncrypted ?: throw ApiError("payment_instructions_unavailable")
                runCatching { crypto.decrypt(payload) }
                    .getOrElse { throw ApiError("payment_instructions_unavailable") }
            }
            PaymentMethodMode.MANUAL_SEND -> {
                val stored = paymentDetailsRepository.getByOrder(orderId)?.text ?: return PaymentInstructions(
                    methodType = methodType,
                    mode = method.mode,
                    text = "ожидаем реквизиты"
                )
                runCatching { crypto.decrypt(stored) }
                    .getOrElse { "ожидаем реквизиты" }
            }
        }
        return PaymentInstructions(methodType = methodType, mode = method.mode, text = text)
    }

    suspend fun submitClaim(
        orderId: String,
        buyerId: Long,
        txid: String?,
        comment: String?,
        attachments: List<PaymentClaimAttachment>
    ): OrderPaymentClaim {
        return lockManager.withLock("order:$orderId:payment_claim", LOCK_WAIT_MS, LOCK_LEASE_MS) {
            val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
            ensureOwner(order, buyerId)
            val methodType = order.paymentMethodType
                ?: throw ApiError("payment_method_not_selected", HttpStatusCode.Conflict)
            val method = paymentMethodsRepository.getEnabledMethod(order.merchantId, methodType)
                ?: throw ApiError("payment_method_unavailable", HttpStatusCode.Conflict)
            val normalized = validateClaimInput(methodType, txid, comment, attachments)
            if (order.status == OrderStatus.PAYMENT_UNDER_REVIEW) {
                val existing = paymentClaimsRepository.getSubmittedByOrder(orderId)
                if (existing != null) return@withLock existing
                throw ApiError("payment_claim_already_submitted", HttpStatusCode.Conflict)
            }
            if (order.status == OrderStatus.AWAITING_PAYMENT_DETAILS) {
                throw ApiError("payment_details_pending", HttpStatusCode.Conflict)
            }
            if (order.status != OrderStatus.AWAITING_PAYMENT) {
                throw ApiError("payment_claim_not_allowed", HttpStatusCode.Conflict)
            }

            val now = Instant.now(clock)
            val claim = createSubmittedClaim(orderId, methodType, normalized.txid, normalized.comment, now)
            ordersRepository.setPaymentClaimed(orderId, now)
            ordersRepository.setStatus(orderId, OrderStatus.PAYMENT_UNDER_REVIEW)
            appendHistory(orderId, OrderStatus.PAYMENT_UNDER_REVIEW, "payment_claim_submitted", buyerId)
            logStatusChange(order, OrderStatus.PAYMENT_UNDER_REVIEW)

            extendHoldsToReviewWindow(order, now)

            val storedAttachments = storeAttachments(orderId, claim.id, attachments, now)
            notifier.notifyAdminClaim(order, claim, storedAttachments.size, method.mode)
            claim
        }
    }

    suspend fun setPaymentDetails(orderId: String, adminId: Long, text: String): Order {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > MAX_DETAILS_LENGTH) {
            throw ApiError("invalid_payment_details")
        }
        val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
        val methodType = order.paymentMethodType
            ?: throw ApiError("payment_method_not_selected", HttpStatusCode.Conflict)
        val method = paymentMethodsRepository.getEnabledMethod(order.merchantId, methodType)
            ?: throw ApiError("payment_method_unavailable", HttpStatusCode.Conflict)
        if (method.mode != PaymentMethodMode.MANUAL_SEND) {
            throw ApiError("payment_details_not_allowed", HttpStatusCode.Conflict)
        }
        val now = Instant.now(clock)
        val encrypted = crypto.encrypt(normalized)
        paymentDetailsRepository.upsert(
            OrderPaymentDetails(orderId = orderId, providedByAdminId = adminId, text = encrypted, createdAt = now)
        )
        if (order.status == OrderStatus.AWAITING_PAYMENT_DETAILS) {
            OrderStatusTransitions.requireAllowed(order.status, OrderStatus.AWAITING_PAYMENT)
            ordersRepository.setStatus(orderId, OrderStatus.AWAITING_PAYMENT)
            appendHistory(orderId, OrderStatus.AWAITING_PAYMENT, "payment_details_sent", adminId)
            logStatusChange(order, OrderStatus.AWAITING_PAYMENT)
        }
        return ordersRepository.get(orderId) ?: order
    }

    suspend fun confirmPayment(orderId: String, adminId: Long): Order {
        return lockManager.withLock("order:$orderId:payment_confirm", LOCK_WAIT_MS, LOCK_LEASE_MS) {
            val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
            if (order.status == OrderStatus.PAID_CONFIRMED ||
                order.status == OrderStatus.fulfillment ||
                order.status == OrderStatus.shipped ||
                order.status == OrderStatus.delivered
            ) {
                val claim = paymentClaimsRepository.getSubmittedByOrder(orderId)
                claim?.let { paymentClaimsRepository.setStatus(it.id, PaymentClaimStatus.ACCEPTED, it.comment) }
                return@withLock order
            }
            if (order.status != OrderStatus.PAYMENT_UNDER_REVIEW) {
                throw ApiError("payment_confirm_not_allowed", HttpStatusCode.Conflict)
            }
            val claim = paymentClaimsRepository.getSubmittedByOrder(orderId)
            val lines = orderLinesRepository.listByOrder(orderId)
            if (!decrementStock(order, lines)) {
                claim?.let { paymentClaimsRepository.setStatus(it.id, PaymentClaimStatus.REJECTED, "stock_mismatch") }
                OrderStatusTransitions.requireAllowed(order.status, OrderStatus.canceled)
                ordersRepository.setStatus(orderId, OrderStatus.canceled)
                appendHistory(orderId, OrderStatus.canceled, "stock_mismatch", adminId)
                logStatusChange(order, OrderStatus.canceled)
                orderHoldService.release(orderId, buildOrderHoldRequests(order, lines))
                holdService.deleteReserveByOrder(orderId)
                throw ApiError("stock_mismatch", HttpStatusCode.Conflict)
            }
            OrderStatusTransitions.requireAllowed(order.status, OrderStatus.PAID_CONFIRMED)
            ordersRepository.setStatus(orderId, OrderStatus.PAID_CONFIRMED)
            appendHistory(orderId, OrderStatus.PAID_CONFIRMED, "payment_confirmed", adminId)
            logStatusChange(order, OrderStatus.PAID_CONFIRMED)
            orderHoldService.release(orderId, buildOrderHoldRequests(order, lines))
            holdService.deleteReserveByOrder(orderId)
            claim?.let { paymentClaimsRepository.setStatus(it.id, PaymentClaimStatus.ACCEPTED, it.comment) }
            ordersRepository.get(orderId) ?: order.copy(status = OrderStatus.PAID_CONFIRMED)
        }
    }

    suspend fun rejectPayment(orderId: String, adminId: Long, reason: String): Order {
        val normalized = reason.trim()
        if (normalized.isEmpty() || normalized.length > MAX_REASON_LENGTH) {
            throw ApiError("invalid_reject_reason")
        }
        return lockManager.withLock("order:$orderId:payment_reject", LOCK_WAIT_MS, LOCK_LEASE_MS) {
            val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
            if (order.status == OrderStatus.canceled ||
                order.status == OrderStatus.AWAITING_PAYMENT ||
                order.status == OrderStatus.AWAITING_PAYMENT_DETAILS
            ) {
                return@withLock order
            }
            if (order.status != OrderStatus.PAYMENT_UNDER_REVIEW) {
                throw ApiError("payment_reject_not_allowed", HttpStatusCode.Conflict)
            }
            val claim = paymentClaimsRepository.getSubmittedByOrder(orderId)
            claim?.let { paymentClaimsRepository.setStatus(it.id, PaymentClaimStatus.REJECTED, normalized) }
            val merchant = merchantsRepository.getById(order.merchantId)
                ?: throw ApiError("merchant_not_found", HttpStatusCode.NotFound)
            val now = Instant.now(clock)
            val claimDeadline = order.createdAt.plusSeconds(merchant.paymentClaimWindowSeconds.toLong())
            val lines = orderLinesRepository.listByOrder(orderId)
            if (now.isAfter(claimDeadline)) {
                ordersRepository.setStatus(orderId, OrderStatus.canceled)
                appendHistory(orderId, OrderStatus.canceled, "PAYMENT_TIMEOUT", adminId)
                logStatusChange(order, OrderStatus.canceled)
                orderHoldService.release(orderId, buildOrderHoldRequests(order, lines))
                holdService.deleteReserveByOrder(orderId)
                return@withLock ordersRepository.get(orderId) ?: order.copy(status = OrderStatus.canceled)
            }
            val targetStatus = resolveAwaitingStatus(orderId, resolveMethod(order))
            OrderStatusTransitions.requireAllowed(order.status, targetStatus)
            ordersRepository.clearPaymentClaimedAt(orderId)
            ordersRepository.setStatus(orderId, targetStatus)
            appendHistory(orderId, targetStatus, "payment_rejected:$normalized", adminId)
            logStatusChange(order, targetStatus)
            val ttlRemaining = max(Duration.between(now, claimDeadline).seconds, 1L)
            orderHoldService.extend(orderId, buildOrderHoldRequests(order, lines), ttlRemaining)
            ordersRepository.get(orderId) ?: order.copy(status = targetStatus)
        }
    }

    suspend fun presignAttachmentUrl(orderId: String, attachmentId: Long, ttl: Duration): String {
        val attachment = attachmentsRepository.getById(attachmentId)
            ?: throw ApiError("attachment_not_found", HttpStatusCode.NotFound)
        if (attachment.orderId != orderId) {
            throw ApiError("attachment_not_found", HttpStatusCode.NotFound)
        }
        val key = attachment.storageKey ?: throw ApiError("attachment_unavailable", HttpStatusCode.Conflict)
        return storage.presignGet(key, ttl)
    }

    suspend fun requestClarification(orderId: String): Order {
        val order = ordersRepository.get(orderId) ?: throw ApiError("order_not_found", HttpStatusCode.NotFound)
        if (order.status != OrderStatus.PAYMENT_UNDER_REVIEW) {
            throw ApiError("payment_clarification_not_allowed", HttpStatusCode.Conflict)
        }
        notifier.notifyBuyerClarification(order)
        return order
    }

    private suspend fun resolveAwaitingStatus(orderId: String, method: MerchantPaymentMethod): OrderStatus {
        return if (method.mode == PaymentMethodMode.MANUAL_SEND) {
            val details = paymentDetailsRepository.getByOrder(orderId)
            if (details == null) OrderStatus.AWAITING_PAYMENT_DETAILS else OrderStatus.AWAITING_PAYMENT
        } else {
            OrderStatus.AWAITING_PAYMENT
        }
    }

    private suspend fun resolveMethod(order: Order): MerchantPaymentMethod {
        val methodType = order.paymentMethodType
            ?: throw ApiError("payment_method_not_selected", HttpStatusCode.Conflict)
        return paymentMethodsRepository.getMethod(order.merchantId, methodType)
            ?: throw ApiError("payment_method_unavailable", HttpStatusCode.Conflict)
    }

    private suspend fun createSubmittedClaim(
        orderId: String,
        methodType: PaymentMethodType,
        txid: String?,
        comment: String?,
        now: Instant
    ): OrderPaymentClaim {
        val claim = OrderPaymentClaim(
            id = 0,
            orderId = orderId,
            methodType = methodType,
            txid = txid,
            comment = comment,
            createdAt = now,
            status = PaymentClaimStatus.SUBMITTED
        )
        val id = runCatching { paymentClaimsRepository.insertClaim(claim) }
            .getOrElse { error ->
                if (error is ExposedSQLException) {
                    val existing = paymentClaimsRepository.getSubmittedByOrder(orderId)
                    if (existing != null) return existing
                }
                throw error
            }
        return claim.copy(id = id)
    }

    private fun validateClaimInput(
        methodType: PaymentMethodType,
        txid: String?,
        comment: String?,
        attachments: List<PaymentClaimAttachment>
    ): NormalizedClaimInput {
        val normalizedTxid = txid?.trim().orEmpty()
        val resolvedTxid = if (normalizedTxid.isEmpty()) {
            if (methodType.isCrypto()) {
                throw ApiError("txid_required")
            }
            null
        } else {
            if (normalizedTxid.length > MAX_TXID_LENGTH || !normalizedTxid.isAsciiToken()) {
                throw ApiError("invalid_txid")
            }
            normalizedTxid
        }

        val normalizedComment = comment?.trim()
        if (normalizedComment != null) {
            if (normalizedComment.isEmpty() || normalizedComment.length > MAX_COMMENT_LENGTH || !normalizedComment.isValidComment()) {
                throw ApiError("invalid_comment")
            }
        }

        if (attachments.size > MAX_ATTACHMENTS) {
            throw ApiError("too_many_attachments")
        }
        attachments.forEach { attachment ->
            val normalized = normalizeMimeType(attachment.contentType)
            if (!ALLOWED_MIME_TYPES.contains(normalized)) {
                throw ApiError("invalid_attachment_type")
            }
            if (attachment.bytes.size.toLong() > MAX_ATTACHMENT_BYTES) {
                throw ApiError("attachment_too_large")
            }
        }

        return NormalizedClaimInput(
            txid = resolvedTxid,
            comment = normalizedComment
        )
    }

    private fun PaymentMethodType.isCrypto(): Boolean = this == PaymentMethodType.MANUAL_CRYPTO

    private fun String.isAsciiToken(): Boolean = all { it.code in 33..126 }

    private fun String.isValidComment(): Boolean = all { char ->
        char == '\n' || char == '\r' || char == '\t' || !char.isISOControl()
    }

    private fun normalizeMimeType(value: String): String {
        val trimmed = value.trim()
        val parsed = runCatching { ContentType.parse(trimmed).withoutParameters().toString() }.getOrNull()
        return (parsed ?: trimmed.substringBefore(";")).trim().lowercase()
    }

    private suspend fun storeAttachments(
        orderId: String,
        claimId: Long,
        attachments: List<PaymentClaimAttachment>,
        now: Instant
    ): List<Long> {
        if (attachments.isEmpty()) return emptyList()
        val results = mutableListOf<Long>()
        attachments.forEach { attachment ->
            val key = buildStorageKey(orderId)
            val stream = ByteArrayInputStream(attachment.bytes)
            val mime = normalizeMimeType(attachment.contentType)
            storage.putObject(stream, key, mime, attachment.bytes.size.toLong())
            val record = OrderAttachment(
                id = 0,
                orderId = orderId,
                claimId = claimId,
                kind = OrderAttachmentKind.PAYMENT_PROOF,
                storageKey = key,
                telegramFileId = null,
                mime = mime,
                size = attachment.bytes.size.toLong(),
                createdAt = now
            )
            val id = attachmentsRepository.create(record)
            results.add(id)
        }
        return results
    }

    private fun buildStorageKey(orderId: String): String {
        val suffix = UUID.randomUUID().toString()
        return "order/$orderId/$suffix"
    }

    private fun ensureOwner(order: Order, buyerId: Long) {
        if (order.userId != buyerId) {
            throw ApiError("forbidden", HttpStatusCode.Forbidden)
        }
    }

    private suspend fun appendHistory(orderId: String, status: OrderStatus, comment: String?, actorId: Long?) {
        orderStatusHistoryRepository.append(
            OrderStatusEntry(
                id = 0,
                orderId = orderId,
                status = status,
                comment = comment,
                actorId = actorId,
                ts = Instant.now(clock)
            )
        )
    }

    private suspend fun logStatusChange(order: Order, status: OrderStatus) {
        runCatching {
            eventLogRepository.insert(
                EventLogEntry(
                    ts = Instant.now(clock),
                    eventType = "status_changed",
                    buyerUserId = order.userId,
                    merchantId = order.merchantId,
                    storefrontId = null,
                    channelId = null,
                    postMessageId = null,
                    listingId = order.itemId,
                    variantId = order.variantId,
                    metadataJson = """{"status":"${status.name}"}"""
                )
            )
        }.onFailure { error ->
            log.warn(
                "event_log_insert_failed eventType=status_changed orderId={} status={} reason={}",
                order.id,
                status,
                error.message
            )
        }
    }

    private suspend fun extendHoldsToReviewWindow(order: Order, now: Instant) {
        val merchant = merchantsRepository.getById(order.merchantId)
            ?: throw ApiError("merchant_not_found", HttpStatusCode.NotFound)
        val claimedAt = ordersRepository.get(order.id)?.paymentClaimedAt ?: now
        val reviewDeadline = claimedAt.plusSeconds(merchant.paymentReviewWindowSeconds.toLong())
        val ttlRemaining = max(Duration.between(now, reviewDeadline).seconds, 1L)
        val lines = orderLinesRepository.listByOrder(order.id)
        orderHoldService.extend(order.id, buildOrderHoldRequests(order, lines), ttlRemaining)
    }

    private suspend fun decrementStock(order: Order, lines: List<OrderLine>): Boolean {
        if (lines.isNotEmpty()) {
            val decrements = lines
                .filter { it.variantId != null }
                .groupBy { it.variantId!! }
                .mapValues { (_, group) -> group.sumOf { it.qty } }
            if (decrements.isEmpty()) return true
            return variantsRepository.decrementStockBatch(decrements)
        }
        val variantId = order.variantId ?: return true
        val qty = order.qty ?: return false
        if (qty <= 0) return false
        return variantsRepository.decrementStockBatch(mapOf(variantId to qty))
    }

    private fun buildOrderHoldRequests(order: Order, lines: List<OrderLine>): List<OrderHoldRequest> {
        if (lines.isNotEmpty()) {
            return lines
                .groupBy { it.variantId ?: it.listingId }
                .values
                .map { group ->
                    val first = group.first()
                    OrderHoldRequest(
                        listingId = first.listingId,
                        variantId = first.variantId,
                        qty = group.sumOf { it.qty }
                    )
                }
        }
        val itemId = order.itemId ?: return emptyList()
        val qty = order.qty ?: 1
        return listOf(OrderHoldRequest(listingId = itemId, variantId = order.variantId, qty = qty))
    }

    companion object {
        private const val LOCK_WAIT_MS = 2_000L
        private const val LOCK_LEASE_MS = 15_000L
        private const val MAX_TXID_LENGTH = 128
        private const val MAX_COMMENT_LENGTH = 1000
        private const val MAX_REASON_LENGTH = 200
        private const val MAX_DETAILS_LENGTH = 4000
        private const val MAX_ATTACHMENTS = 5
        private const val MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L
        private val SELECT_ALLOWED_STATUSES = setOf(
            OrderStatus.pending,
            OrderStatus.AWAITING_PAYMENT_DETAILS,
            OrderStatus.AWAITING_PAYMENT
        )
        private val ALLOWED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf"
        )
    }
}

package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.db.OrdersRepository
import com.example.domain.Order
import com.example.domain.hold.LockManager
import com.pengrad.telegrambot.model.request.LabeledPrice
import com.pengrad.telegrambot.model.request.ShippingOption
import com.pengrad.telegrambot.request.AnswerPreCheckoutQuery
import com.pengrad.telegrambot.request.AnswerShippingQuery
import com.pengrad.telegrambot.request.SendInvoice
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import org.slf4j.LoggerFactory

interface PaymentsService {
    suspend fun createAndSendInvoice(order: Order, itemTitle: String, photoUrl: String? = null): InvoiceResult
    suspend fun answerShipping(
        queryId: String,
        options: List<ShippingOption>,
        ok: Boolean = true,
        errorText: String? = null
    )

    suspend fun answerPreCheckout(queryId: String, ok: Boolean, errorText: String? = null)
}

data class InvoiceResult(
    val invoiceMessageId: Int,
    val sent: Boolean
)

internal const val ORDER_PAYLOAD_PREFIX = "order:"

internal fun buildOrderPayload(orderId: String): String = ORDER_PAYLOAD_PREFIX + orderId

private const val GOODS_PRICE_LABEL = "Goods"
private const val LOCK_WAIT_MS = 2_000L
private const val LOCK_LEASE_MS = 15_000L

class PaymentsServiceImpl(
    private val config: AppConfig,
    private val clients: TelegramClients,
    private val ordersRepository: OrdersRepository,
    private val lockManager: LockManager
) : PaymentsService {

    private val log = LoggerFactory.getLogger(PaymentsServiceImpl::class.java)

    override suspend fun createAndSendInvoice(order: Order, itemTitle: String, photoUrl: String?): InvoiceResult {
        return lockManager.withLock("order:${order.id}", LOCK_WAIT_MS, LOCK_LEASE_MS) {
            val fresh = ordersRepository.get(order.id) ?: error("Order not found: ${order.id}")
            fresh.invoiceMessageId?.let { existing ->
                return@withLock InvoiceResult(existing, sent = false)
            }

            val goodsPrice = LabeledPrice(GOODS_PRICE_LABEL, order.amountMinor.toMinorInt("order.amountMinor"))
            val invoice = SendInvoice(
                order.userId,
                itemTitle,
                itemTitle,
                buildOrderPayload(order.id),
                config.payments.invoiceCurrency,
                listOf(goodsPrice)
            )
                .providerToken(config.payments.providerToken)
                .needName(true)
                .needPhoneNumber(true)
                .needEmail(true)

            if (photoUrl != null) {
                invoice.photoUrl(photoUrl)
            }
            if (config.payments.shippingEnabled) {
                invoice.needShippingAddress(true)
                invoice.isFlexible(true)
            }
            if (config.payments.allowTips) {
                val tips = config.payments.suggestedTipAmountsMinor
                if (tips.isNotEmpty()) {
                    val maxTip = tips.maxOrNull()
                    if (maxTip != null && maxTip > 0) {
                        invoice.maxTipAmount(maxTip)
                    }
                    invoice.suggestedTipAmounts(tips)
                }
            }

            val response: SendResponse = clients.shopBot.execute(invoice)
            if (!response.isOk) {
                error("sendInvoice failed: ${response.errorCode()} ${response.description()}")
            }

            val messageId = response.message()?.messageId()
                ?: error("sendInvoice missing messageId")
            ordersRepository.setInvoiceMessage(order.id, messageId)
            log.info("Invoice sent orderId={} messageId={}", order.id, messageId)
            InvoiceResult(invoiceMessageId = messageId, sent = true)
        }
    }

    override suspend fun answerShipping(
        queryId: String,
        options: List<ShippingOption>,
        ok: Boolean,
        errorText: String?
    ) {
        val request = if (ok) {
            require(options.isNotEmpty()) { "Shipping options are required when ok=true" }
            buildShippingAnswer(queryId, options)
        } else {
            val message = errorText?.takeIf { it.isNotBlank() }
                ?: error("Shipping errorText is required when ok=false")
            AnswerShippingQuery(queryId, message)
        }
        val response: BaseResponse = clients.shopBot.execute(request)
        if (!response.isOk) {
            log.warn("answerShipping failed: {} {}", response.errorCode(), response.description())
        }
    }

    override suspend fun answerPreCheckout(queryId: String, ok: Boolean, errorText: String?) {
        val request = if (ok) {
            AnswerPreCheckoutQuery(queryId)
        } else {
            val message = errorText?.takeIf { it.isNotBlank() }
                ?: error("Pre-checkout errorText is required when ok=false")
            AnswerPreCheckoutQuery(queryId, message)
        }
        val response: BaseResponse = clients.shopBot.execute(request)
        if (!response.isOk) {
            log.warn("answerPreCheckout failed: {} {}", response.errorCode(), response.description())
        }
    }

    private fun Long.toMinorInt(field: String): Int {
        require(this in Int.MIN_VALUE..Int.MAX_VALUE) { "$field is out of Int range" }
        return this.toInt()
    }
}

@Suppress("SpreadOperator")
private fun buildShippingAnswer(queryId: String, options: List<ShippingOption>): AnswerShippingQuery {
    return AnswerShippingQuery(queryId, *options.toTypedArray())
}


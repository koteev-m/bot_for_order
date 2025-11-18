package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.services.ORDER_PAYLOAD_PREFIX
import com.example.bots.TelegramClients
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.LabeledPrice
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.model.request.ShippingOption
import com.pengrad.telegrambot.request.SendMessage
import java.time.Instant
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal const val SHIPPING_STD_OPTION_ID = "std"
internal const val SHIPPING_EXP_OPTION_ID = "exp"
internal const val SHIPPING_PICKUP_OPTION_ID = "pickup"

internal val shopLog: Logger = LoggerFactory.getLogger("ShopWebhook")

internal fun formatMoney(amountMinor: Long, currency: String): String {
    val neg = amountMinor < 0
    val abs = kotlin.math.abs(amountMinor)
    val major = abs / 100
    val minor = (abs % 100).toInt()
    val num = "%d.%02d".format(major, minor)
    return (if (neg) "-" else "") + num + " " + currency.uppercase()
}

internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun parseOrderIdFromPayload(payload: String): String? {
    if (!payload.startsWith(ORDER_PAYLOAD_PREFIX)) return null
    return payload.removePrefix(ORDER_PAYLOAD_PREFIX).takeIf { it.isNotBlank() }
}

internal fun buildShippingOptions(cfg: AppConfig): List<ShippingOption> {
    val payments = cfg.payments
    val stdPrice = LabeledPrice("Standard", payments.shippingBaseStdMinor.toMinorPrice("SHIPPING_BASE_STD_MINOR"))
    val expPrice = LabeledPrice("Express", payments.shippingBaseExpMinor.toMinorPrice("SHIPPING_BASE_EXP_MINOR"))
    val pickupPrice = LabeledPrice("Pickup", 0)
    return listOf(
        ShippingOption(SHIPPING_STD_OPTION_ID, "Standard", stdPrice),
        ShippingOption(SHIPPING_EXP_OPTION_ID, "Express", expPrice),
        ShippingOption(SHIPPING_PICKUP_OPTION_ID, "Pickup", pickupPrice)
    )
}

private fun Long.toMinorPrice(field: String): Int {
    require(this in Int.MIN_VALUE..Int.MAX_VALUE) { "$field is out of range" }
    return this.toInt()
}

internal fun TelegramClients.replyShopHtml(chatId: Long, text: String) {
    shopBot.execute(
        SendMessage(chatId, text)
            .parseMode(ParseMode.HTML)
            .disablePreview()
    )
}

internal suspend fun sendItemCard(chatId: Long, itemId: String, deps: ShopWebhookDeps) {
    val item = deps.itemsRepository.getById(itemId) ?: run {
        deps.clients.replyShopHtml(
            chatId,
            "❌ Товар не найден: <code>${escapeHtml(itemId)}</code>"
        )
        return
    }
    val price = deps.pricesRepository.get(item.id)
    val variants = deps.variantsRepository.listByItem(item.id)

    val priceLine = price?.let {
        "Цена: <b>${escapeHtml(formatMoney(it.baseAmountMinor, it.baseCurrency))}</b>"
    } ?: "Цена: <i>уточняется</i>"

    val sizes = variants.mapNotNull { it.size }.distinct()
    val sizesLine = if (sizes.isNotEmpty()) "Варианты: ${escapeHtml(sizes.joinToString(", "))}" else null

    val caption = buildString {
        append("<b>").append(escapeHtml(item.title)).append("</b>\n")
        append(escapeHtml(item.description.take(400))).append("\n")
        append(priceLine)
        sizesLine?.let {
            append("\n").append(it)
        }
    }

    val miniAppUrl = "${deps.config.server.publicBaseUrl.removeSuffix("/")}/app/?item=${item.id}"
    val kb = InlineKeyboardMarkup(
        InlineKeyboardButton("Оформить").url(miniAppUrl)
    )

    deps.clients.shopBot.execute(
        SendMessage(chatId, caption)
            .parseMode(ParseMode.HTML)
            .disablePreview()
            .replyMarkup(kb)
    )
}

internal suspend fun decrementStock(order: Order, deps: ShopWebhookDeps): Boolean {
    val variantId = order.variantId ?: return true
    val updated = deps.variantsRepository.decrementStock(variantId, order.qty)
    if (!updated) {
        shopLog.error(
            "order_stock_mismatch orderId={} variant={} qty={}",
            order.id,
            variantId,
            order.qty
        )
    }
    return updated
}

internal suspend fun handleStockFailure(order: Order, deps: ShopWebhookDeps) {
    deps.ordersRepository.setStatus(order.id, OrderStatus.canceled)
    deps.orderStatusRepository.append(
        OrderStatusEntry(
            id = 0,
            orderId = order.id,
            status = OrderStatus.canceled,
            comment = "stock_mismatch",
            actorId = null,
            ts = Instant.now()
        )
    )
    deps.holdService.deleteReserveByOrder(order.id)
    notifyStockIssue(deps, order.id)
}

private fun notifyStockIssue(deps: ShopWebhookDeps, orderId: String) {
    val text = "⚠️ Сток для заказа $orderId закончился после оплаты. Резерв снят."
    deps.config.telegram.adminIds.forEach { adminId ->
        deps.clients.adminBot.execute(SendMessage(adminId, text))
    }
}

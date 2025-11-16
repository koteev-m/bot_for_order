package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.services.ORDER_PAYLOAD_PREFIX
import com.example.bots.TelegramClients
import com.pengrad.telegrambot.model.request.LabeledPrice
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.model.request.ShippingOption
import com.pengrad.telegrambot.request.SendMessage

internal const val SHIPPING_STD_OPTION_ID = "std"
internal const val SHIPPING_EXP_OPTION_ID = "exp"
internal const val SHIPPING_PICKUP_OPTION_ID = "pickup"

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

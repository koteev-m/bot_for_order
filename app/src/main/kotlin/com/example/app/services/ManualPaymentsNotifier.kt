package com.example.app.services

import com.example.app.config.AppConfig
import com.example.bots.TelegramClients
import com.example.domain.Order
import com.example.domain.OrderPaymentClaim
import com.example.domain.PaymentMethodMode
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage

interface ManualPaymentsNotifier {
    fun notifyAdminClaim(order: Order, claim: OrderPaymentClaim, attachmentCount: Int, mode: PaymentMethodMode)
    fun notifyBuyerClarification(order: Order)
}

class TelegramManualPaymentsNotifier(
    private val config: AppConfig,
    private val clients: TelegramClients
) : ManualPaymentsNotifier {
    override fun notifyAdminClaim(order: Order, claim: OrderPaymentClaim, attachmentCount: Int, mode: PaymentMethodMode) {
        val base = buildString {
            append("🧾 Новый manual payment claim\n")
            append("Заказ: <code>").append(order.id).append("</code>\n")
            append("Метод: ").append(claim.methodType.name).append('\n')
            append("Вложений: ").append(attachmentCount)
        }
        val kb = InlineKeyboardMarkup(
            InlineKeyboardButton("✅ Оплачено").callbackData("payment:confirm:${order.id}"),
            InlineKeyboardButton("❌ Не оплачено").callbackData("payment:reject:${order.id}"),
        )
        kb.addRow(InlineKeyboardButton("🕒 Запросить уточнение").callbackData("payment:clarify:${order.id}"))
        if (mode == PaymentMethodMode.MANUAL_SEND) {
            kb.addRow(InlineKeyboardButton("📤 Отправить реквизиты").callbackData("payment:details:${order.id}"))
        }
        config.telegram.adminIds.forEach { adminId ->
            clients.adminBot.execute(
                SendMessage(adminId, base)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(kb)
            )
        }
    }

    override fun notifyBuyerClarification(order: Order) {
        val text = "🕒 Пожалуйста, уточните оплату по заказу ${order.id} " +
            "и при необходимости загрузите подтверждение ещё раз."
        clients.shopBot.execute(SendMessage(order.userId, text))
    }
}

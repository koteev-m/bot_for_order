package com.example.bots.startapp

import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup

object InlineUrlButton {
    fun buyButton(url: String, text: String = "Купить"): InlineKeyboardMarkup =
        InlineKeyboardMarkup(InlineKeyboardButton(text).url(url))
}

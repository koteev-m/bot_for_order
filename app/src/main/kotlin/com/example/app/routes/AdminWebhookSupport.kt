package com.example.app.routes

import com.example.app.services.ItemsService
import com.example.bots.TelegramClients
import com.pengrad.telegrambot.model.LinkPreviewOptions
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage

internal fun splitCommand(text: String): Pair<String, String> {
    val spaceIndex = text.indexOf(' ')
    return if (spaceIndex == -1) {
        text to ""
    } else {
        text.substring(0, spaceIndex) to text.substring(spaceIndex + 1).trim()
    }
}

internal fun parseNewArgs(args: String): Pair<String, String> {
    if (args.isBlank()) return "Untitled" to "No description"
    val parts = args.split("|", limit = 2).map { it.trim() }
    val title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Untitled"
    val description = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "No description"
    require(title.length in 1..200) { "Title length invalid" }
    require(description.length in 1..4000) { "Description length invalid" }
    return title to description
}

internal val START_REPLY = """
    <b>Admin-панель</b>
    /help — подсказка
    /new [title] | [description] — черновик
    /media <ITEM_ID> — режим сбора 2–10 фото/видео
    /media_done — сохранить собранные медиа в БД
    /media_cancel — отменить сбор
    /preview <ITEM_ID> — предпросмотр альбома
""".trimIndent()

internal val HELP_REPLY = """
    <b>Медиа-режим</b>
    1) /media &lt;ITEM_ID&gt; → бот ждёт 2–10 фото или видео (можно альбомом).
    2) Пришлите медиа (если пришлёте альбом — всё соберётся автоматически).
    3) /media_done → сохранить в БД.
    4) /preview &lt;ITEM_ID&gt; → показать альбом.
    5) /media_cancel → отменить без сохранения.
""".trimIndent()

internal fun buildDraftCreatedReply(id: String): String = buildString {
    appendLine("✅ Черновик создан")
    appendLine("<b>Item ID:</b> <code>$id</code>")
    appendLine()
    appendLine("Дальше:")
    appendLine("1) <i>/media $id</i> — собрать 2–10 фото/видео")
    appendLine("2) <i>/media_done</i> — сохранить медиа")
    appendLine("3) <i>/preview $id</i> — предпросмотр альбома")
    appendLine("4) <i>/post $id</i> — постинг в канал (S9)")
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun handleCreateDraft(
    chatId: Long,
    args: String,
    clients: TelegramClients,
    itemsService: ItemsService
) {
    val (title, description) = try {
        parseNewArgs(args)
    } catch (error: IllegalArgumentException) {
        val message = error.message ?: "Неверный формат команды."
        clients.adminBot.execute(
            SendMessage(chatId, "⚠️ $message")
                .parseMode(ParseMode.HTML)
                .disablePreview()
        )
        return
    }

    val id = try {
        itemsService.createDraft(title, description)
    } catch (error: Exception) {
        val reason = error.message ?: "Неизвестная ошибка"
        clients.adminBot.execute(
            SendMessage(chatId, "⚠️ Не удалось создать черновик: $reason")
                .parseMode(ParseMode.HTML)
                .disablePreview()
        )
        return
    }

    clients.replyHtml(chatId, buildDraftCreatedReply(id))
}

internal fun TelegramClients.replyHtml(chatId: Long, text: String) {
    adminBot.execute(
        SendMessage(chatId, text)
            .parseMode(ParseMode.HTML)
            .disablePreview()
    )
}

internal fun SendMessage.disablePreview(): SendMessage =
    linkPreviewOptions(LinkPreviewOptions().isDisabled(true))

package com.example.app.routes

import com.example.app.services.ItemsService
import com.example.app.services.OrderStatusService
import com.example.bots.TelegramClients
import com.example.domain.OrderStatus
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

internal const val STATUS_COMMAND = "/status"
internal const val STATUS_USAGE =
    "/status <ORDER_ID> <paid|fulfillment|shipped|delivered|canceled> [comment]"

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
    /media <ITEM_ID> → /media_done → /preview <ITEM_ID>
    <b>/post &lt;ITEM_ID&gt;</b> — опубликовать в канал с кнопкой «Купить»
""".trimIndent()

internal val HELP_REPLY = """
    <b>Публикация</b>
    1) /media &lt;ITEM_ID&gt; → собрать 2–10 фото/видео → /media_done
    2) /preview &lt;ITEM_ID&gt; → проверить альбом
    3) <b>/post &lt;ITEM_ID&gt;</b> → отправить альбом в канал и добавить кнопку «Купить»
    В канале используется URL-кнопка (Direct Link Mini App ?startapp=).
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

@Suppress("TooGenericExceptionCaught")
internal suspend fun handleStatusCommand(
    args: String,
    actorId: Long,
    service: OrderStatusService,
    reply: (String) -> Unit
) {
    val parsed = try {
        parseStatusArgs(args)
    } catch (error: IllegalArgumentException) {
        val message = error.message ?: STATUS_USAGE
        reply("⚠️ $message")
        return
    }

    val commentDisplay = parsed.comment ?: "none"
    try {
        val result = service.changeStatus(parsed.orderId, parsed.status, actorId, parsed.comment)
        reply("ОК: order=${result.order.id}, status=${result.order.status.name}, note=$commentDisplay")
    } catch (error: Exception) {
        val reason = error.message ?: "Не удалось изменить статус"
        reply("⚠️ $reason")
    }
}

private fun parseStatusArgs(args: String): StatusCommandArgs {
    val trimmed = args.trim()
    val parts = trimmed.split(" ", limit = 3)
    val orderId = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
    val statusRaw = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    if (orderId == null || statusRaw == null) {
        throw IllegalArgumentException(STATUS_USAGE)
    }
    val status = OrderStatus.entries.firstOrNull { it.name.equals(statusRaw, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown status: $statusRaw")
    val comment = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
    return StatusCommandArgs(orderId = orderId, status = status, comment = comment)
}

private data class StatusCommandArgs(
    val orderId: String,
    val status: OrderStatus,
    val comment: String?
)

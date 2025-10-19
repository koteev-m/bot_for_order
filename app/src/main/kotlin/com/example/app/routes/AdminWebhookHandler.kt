package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.services.ItemsService
import com.example.app.tg.TgUpdate
import com.example.bots.TelegramClients
import com.pengrad.telegrambot.model.LinkPreviewOptions
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun Application.installAdminWebhook() {
    val log = LoggerFactory.getLogger("AdminWebhook")

    val cfg by inject<AppConfig>()
    val clients by inject<TelegramClients>()
    val itemsService by inject<ItemsService>()

    val json = Json { ignoreUnknownKeys = true }
    val deps = AdminWebhookDeps(json, log, cfg, clients, itemsService)

    routing {
        post("/tg/admin") {
            val body = call.receiveText()
            call.processAdminUpdate(body, deps)
        }
    }
}

private data class AdminWebhookDeps(
    val json: Json,
    val log: Logger,
    val config: AppConfig,
    val clients: TelegramClients,
    val itemsService: ItemsService
)

private data class AdminCommand(
    val chatId: Long,
    val fromId: Long,
    val command: String,
    val args: String
)

private suspend fun ApplicationCall.processAdminUpdate(
    body: String,
    deps: AdminWebhookDeps
) {
    val update = runCatching { deps.json.decodeFromString(TgUpdate.serializer(), body) }
        .onFailure { deps.log.warn("Invalid update json: {}", it.message) }
        .getOrNull()

    val command = update?.let(::extractAdminCommand)

    command?.let {
        if (!deps.config.telegram.adminIds.contains(it.fromId)) {
            deps.clients.adminBot.execute(
                SendMessage(it.chatId, "⛔ Команда доступна только администраторам.")
                    .parseMode(ParseMode.HTML)
                    .disablePreview()
            )
        } else {
            when (it.command) {
                "/start" -> deps.clients.replyHtml(it.chatId, START_REPLY)
                "/help" -> deps.clients.replyHtml(it.chatId, HELP_REPLY)
                "/new" -> handleCreateDraft(it.chatId, it.args, deps.clients, deps.itemsService)
                else -> deps.clients.replyHtml(
                    it.chatId,
                    "Неизвестная команда. Напишите <code>/help</code>."
                )
            }
        }
    }

    respond(HttpStatusCode.OK)
}

private fun splitCommand(text: String): Pair<String, String> {
    val spaceIndex = text.indexOf(' ')
    return if (spaceIndex == -1) {
        text to ""
    } else {
        text.substring(0, spaceIndex) to text.substring(spaceIndex + 1).trim()
    }
}

private fun extractAdminCommand(update: TgUpdate): AdminCommand? {
    val message = update.message
    val fromId = message?.from?.id
    val text = message?.text?.trim().orEmpty()
    val isCommand = text.isNotBlank() && text.startsWith("/")
    if (message == null || fromId == null || !isCommand) {
        return null
    }
    val (command, args) = splitCommand(text)
    return AdminCommand(
        chatId = message.chat.id,
        fromId = fromId,
        command = command,
        args = args
    )
}

private fun parseNewArgs(args: String): Pair<String, String> {
    if (args.isBlank()) return "Untitled" to "No description"
    val parts = args.split("|", limit = 2).map { it.trim() }
    val title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Untitled"
    val description = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "No description"
    require(title.length in 1..200) { "Title length invalid" }
    require(description.length in 1..4000) { "Description length invalid" }
    return title to description
}

private val START_REPLY = """
    <b>Admin-панель</b>
    Доступные команды:
    • <code>/help</code> — подсказка
    • <code>/new [title] | [description]</code> — создать черновик лота
""".trimIndent()

private val HELP_REPLY = """
    <b>Справка</b>
    • <code>/new</code> — создать черновик: <i>/new Nike Dunk | 42 EU, состояние 9/10</i>
    Далее добавьте медиа и опубликуйте в канал на шагах S8–S9.
""".trimIndent()

private fun buildDraftCreatedReply(id: String): String = buildString {
    appendLine("✅ Черновик создан")
    appendLine("<b>Item ID:</b> <code>$id</code>")
    appendLine()
    appendLine("Дальше:")
    appendLine("1) <i>/media $id</i> — добавить 2–10 фото/видео (S8)")
    appendLine("2) <i>/preview $id</i> — предпросмотр альбома (S8)")
    appendLine("3) <i>/post $id</i> — постинг в канал (S9)")
}

private fun TelegramClients.replyHtml(chatId: Long, text: String) {
    adminBot.execute(
        SendMessage(chatId, text)
            .parseMode(ParseMode.HTML)
            .disablePreview()
    )
}

@Suppress("TooGenericExceptionCaught")
private suspend fun handleCreateDraft(
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

private fun SendMessage.disablePreview(): SendMessage =
    linkPreviewOptions(LinkPreviewOptions().isDisabled(true))

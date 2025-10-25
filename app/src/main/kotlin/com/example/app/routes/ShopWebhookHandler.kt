package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.tg.TgUpdate
import com.example.bots.TelegramClients
import com.example.bots.startapp.StartAppCodec
import com.example.db.ItemsRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

fun Application.installShopWebhook() {
    val cfg by inject<AppConfig>()
    val clients by inject<TelegramClients>()
    val itemsRepo by inject<ItemsRepository>()
    val pricesRepo by inject<PricesDisplayRepository>()
    val variantsRepo by inject<VariantsRepository>()

    val deps = ShopWebhookDeps(
        config = cfg,
        clients = clients,
        itemsRepository = itemsRepo,
        pricesRepository = pricesRepo,
        variantsRepository = variantsRepo,
        json = Json { ignoreUnknownKeys = true }
    )

    routing {
        post("/tg/shop") {
            val body = call.receiveText()
            handleShopUpdate(call, body, deps)
        }
    }
}

private data class ShopWebhookDeps(
    val config: AppConfig,
    val clients: TelegramClients,
    val itemsRepository: ItemsRepository,
    val pricesRepository: PricesDisplayRepository,
    val variantsRepository: VariantsRepository,
    val json: Json
)

private suspend fun handleShopUpdate(call: ApplicationCall, body: String, deps: ShopWebhookDeps) {
    val update = runCatching { deps.json.decodeFromString(TgUpdate.serializer(), body) }.getOrNull()
    val message = update?.message
    val text = message?.text?.trim().orEmpty()

    if (update == null || message == null || !text.startsWith("/")) {
        call.respond(HttpStatusCode.OK)
        return
    }

    val chatId = message.chat.id
    val (command, args) = splitCommand(text)
    when (command) {
        "/start" -> handleStart(chatId, args, deps)
        "/open" -> handleOpen(chatId, args, deps)
        else -> {
            // игнорируем прочие команды
        }
    }

    call.respond(HttpStatusCode.OK)
}

private suspend fun handleStart(chatId: Long, args: String, deps: ShopWebhookDeps) {
    if (args.isBlank()) {
        deps.clients.replyHtml(chatId, WELCOME_MESSAGE)
        return
    }

    val param = runCatching { StartAppCodec.decode(args) }.getOrElse {
        deps.clients.replyHtml(chatId, INVALID_PARAM_MESSAGE)
        return
    }

    sendItemCard(chatId = chatId, itemId = param.itemId, deps = deps)
}

private suspend fun handleOpen(chatId: Long, args: String, deps: ShopWebhookDeps) {
    val itemId = args.ifBlank { null }
    if (itemId == null) {
        deps.clients.replyHtml(chatId, USAGE_MESSAGE)
        return
    }

    sendItemCard(chatId = chatId, itemId = itemId, deps = deps)
}

private fun formatMoney(amountMinor: Long, currency: String): String {
    val neg = amountMinor < 0
    val abs = kotlin.math.abs(amountMinor)
    val major = abs / 100
    val minor = (abs % 100).toInt()
    val num = "%d.%02d".format(major, minor)
    return (if (neg) "-" else "") + num + " " + currency.uppercase()
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private suspend fun sendItemCard(chatId: Long, itemId: String, deps: ShopWebhookDeps) {
    val item = deps.itemsRepository.getById(itemId) ?: run {
        deps.clients.replyHtml(
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

private val WELCOME_MESSAGE = """
    👋 Добро пожаловать! Отправьте ссылку с параметром или команду <code>/open &lt;ITEM_ID&gt;</code>.
""".trimIndent()

private const val INVALID_PARAM_MESSAGE = "⚠️ Неверный параметр запуска."

private const val USAGE_MESSAGE = "Использование: <code>/open &lt;ITEM_ID&gt;</code>"

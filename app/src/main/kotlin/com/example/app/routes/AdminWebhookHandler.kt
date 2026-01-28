package com.example.app.routes

import com.example.app.config.AppConfig
import com.example.app.services.InventoryService
import com.example.app.services.ItemsService
import com.example.app.services.MediaStateStore
import com.example.app.services.MediaType
import com.example.app.services.OrderStatusService
import com.example.app.services.OffersService
import com.example.app.services.ManualPaymentsService
import com.example.app.services.PendingMedia
import com.example.app.services.PaymentDetailsStateStore
import com.example.app.services.PaymentRejectReasonStateStore
import com.example.app.services.PostService
import com.example.app.tg.TgMessage
import com.example.app.tg.TgUpdate
import com.example.app.tg.TgCallbackQuery
import com.example.bots.TelegramClients
import com.example.db.OrderDeliveryRepository
import com.example.db.OrdersRepository
import com.example.db.ItemMediaRepository
import com.example.domain.ItemMedia
import com.pengrad.telegrambot.model.request.InputMedia
import com.pengrad.telegrambot.model.request.InputMediaPhoto
import com.pengrad.telegrambot.model.request.InputMediaVideo
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.SendMediaGroup
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.request.AnswerCallbackQuery
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

    val config by inject<AppConfig>()
    val clients by inject<TelegramClients>()
    val itemsService by inject<ItemsService>()
    val itemMediaRepository by inject<ItemMediaRepository>()
    val mediaStateStore by inject<MediaStateStore>()
    val postService by inject<PostService>()
    val orderStatusService by inject<OrderStatusService>()
    val offersService by inject<OffersService>()
    val inventoryService by inject<InventoryService>()
    val manualPaymentsService by inject<ManualPaymentsService>()
    val paymentDetailsStateStore by inject<PaymentDetailsStateStore>()
    val paymentRejectReasonStateStore by inject<PaymentRejectReasonStateStore>()
    val ordersRepository by inject<OrdersRepository>()
    val orderDeliveryRepository by inject<OrderDeliveryRepository>()

    val json = Json { ignoreUnknownKeys = true }
    val deps = AdminWebhookDeps(
        json = json,
        log = log,
        config = config,
        clients = clients,
        itemsService = itemsService,
        itemMediaRepository = itemMediaRepository,
        mediaStateStore = mediaStateStore,
        paymentDetailsStateStore = paymentDetailsStateStore,
        paymentRejectReasonStateStore = paymentRejectReasonStateStore,
        postService = postService,
        orderStatusService = orderStatusService,
        offersService = offersService,
        inventoryService = inventoryService,
        manualPaymentsService = manualPaymentsService,
        ordersRepository = ordersRepository,
        orderDeliveryRepository = orderDeliveryRepository
    )

    routing {
        post("/tg/admin") {
            val body = call.receiveText()
            handleAdminUpdate(call, body, deps)
        }
    }
}

private data class AdminWebhookDeps(
    val json: Json,
    val log: Logger,
    val config: AppConfig,
    val clients: TelegramClients,
    val itemsService: ItemsService,
    val itemMediaRepository: ItemMediaRepository,
    val mediaStateStore: MediaStateStore,
    val paymentDetailsStateStore: PaymentDetailsStateStore,
    val paymentRejectReasonStateStore: PaymentRejectReasonStateStore,
    val postService: PostService,
    val orderStatusService: OrderStatusService,
    val offersService: OffersService,
    val inventoryService: InventoryService,
    val manualPaymentsService: ManualPaymentsService,
    val ordersRepository: OrdersRepository,
    val orderDeliveryRepository: OrderDeliveryRepository
)

private suspend fun handleAdminUpdate(
    call: ApplicationCall,
    body: String,
    deps: AdminWebhookDeps
) {
    val update = runCatching { deps.json.decodeFromString(TgUpdate.serializer(), body) }
        .getOrElse {
            deps.log.warn("Invalid update json: {}", it.message)
            call.respond(HttpStatusCode.OK)
            return
        }

    val callback = update.callbackQuery
    if (callback != null) {
        handleAdminCallback(callback, deps)
        call.respond(HttpStatusCode.OK)
        return
    }

    val message = update.message
    val fromId = message?.from?.id
    if (message == null || fromId == null) {
        call.respond(HttpStatusCode.OK)
        return
    }
    val chatId = message.chat.id

    val reply: (String) -> Unit = { html ->
        deps.clients.adminBot.execute(
            SendMessage(chatId, html)
                .parseMode(ParseMode.HTML)
                .disablePreview()
        )
    }

    if (!deps.config.telegram.adminIds.contains(fromId)) {
        reply("⛔ Команда доступна только администраторам.")
    } else {
        val text = message.text?.trim().orEmpty()
        val pendingDetailsOrderId = deps.paymentDetailsStateStore.get(fromId)
        val pendingRejectOrderId = deps.paymentRejectReasonStateStore.get(fromId)
        if (pendingRejectOrderId != null && text.isNotBlank() && !text.startsWith("/")) {
            handlePaymentRejectMessage(chatId, fromId, pendingRejectOrderId, text, deps, reply)
            deps.paymentRejectReasonStateStore.clear(fromId)
        } else if (pendingDetailsOrderId != null && text.isNotBlank() && !text.startsWith("/")) {
            handlePaymentDetailsMessage(chatId, fromId, pendingDetailsOrderId, text, deps, reply)
            deps.paymentDetailsStateStore.clear(fromId)
        } else if (text.startsWith("/")) {
            handleAdminCommand(chatId, fromId, text, deps, reply)
        } else {
            handleMediaCollection(fromId, chatId, message, deps, reply)
        }
    }

    call.respond(HttpStatusCode.OK)
}

private suspend fun handleAdminCallback(callback: TgCallbackQuery, deps: AdminWebhookDeps) {
    val fromId = callback.from.id
    val chatId = callback.message?.chat?.id ?: fromId
    val data = callback.data ?: return
    val reply: (String) -> Unit = { html ->
        deps.clients.adminBot.execute(
            SendMessage(chatId, html)
                .parseMode(ParseMode.HTML)
                .disablePreview()
        )
    }
    if (!deps.config.telegram.adminIds.contains(fromId)) {
        reply("⛔ Команда доступна только администраторам.")
        deps.clients.adminBot.execute(AnswerCallbackQuery(callback.id))
        return
    }
    val parts = data.split(":", limit = 3)
    if (parts.size < 3 || parts[0] != "payment") {
        deps.clients.adminBot.execute(AnswerCallbackQuery(callback.id))
        return
    }
    val action = parts[1]
    val orderId = parts[2]
    when (action) {
        "confirm" -> {
            deps.paymentDetailsStateStore.clear(fromId)
            deps.paymentRejectReasonStateStore.clear(fromId)
            runCatching { deps.manualPaymentsService.confirmPayment(orderId, fromId) }
                .onSuccess { reply("✅ Оплата подтверждена для заказа <code>$orderId</code>.") }
                .onFailure { reply("⚠️ Не удалось подтвердить оплату.") }
        }
        "reject" -> {
            deps.paymentDetailsStateStore.clear(fromId)
            deps.paymentRejectReasonStateStore.start(fromId, orderId)
            reply("❌ Укажите причину отклонения одним сообщением для заказа <code>$orderId</code>. Для отмены: /cancel.")
        }
        "clarify" -> {
            deps.paymentDetailsStateStore.clear(fromId)
            deps.paymentRejectReasonStateStore.clear(fromId)
            runCatching { deps.manualPaymentsService.requestClarification(orderId) }
                .onSuccess { reply("🕒 Запрос на уточнение отправлен для заказа <code>$orderId</code>.") }
                .onFailure { reply("⚠️ Не удалось отправить запрос на уточнение.") }
        }
        "details" -> {
            deps.paymentRejectReasonStateStore.clear(fromId)
            deps.paymentDetailsStateStore.start(fromId, orderId)
            reply("📤 Отправьте реквизиты одним сообщением для заказа <code>$orderId</code>.")
        }
    }
    deps.clients.adminBot.execute(AnswerCallbackQuery(callback.id))
}

private suspend fun handlePaymentDetailsMessage(
    chatId: Long,
    adminId: Long,
    orderId: String,
    text: String,
    deps: AdminWebhookDeps,
    reply: (String) -> Unit
) {
    runCatching { deps.manualPaymentsService.setPaymentDetails(orderId, adminId, text) }
        .onSuccess { reply("✅ Реквизиты сохранены для заказа <code>$orderId</code>.") }
        .onFailure {
            deps.clients.adminBot.execute(
                SendMessage(chatId, "⚠️ Не удалось сохранить реквизиты.")
                    .parseMode(ParseMode.HTML)
                    .disablePreview()
            )
        }
}

private suspend fun handlePaymentRejectMessage(
    chatId: Long,
    adminId: Long,
    orderId: String,
    text: String,
    deps: AdminWebhookDeps,
    reply: (String) -> Unit
) {
    runCatching { deps.manualPaymentsService.rejectPayment(orderId, adminId, text) }
        .onSuccess { reply("❌ Оплата отклонена для заказа <code>$orderId</code>.") }
        .onFailure {
            deps.clients.adminBot.execute(
                SendMessage(chatId, "⚠️ Не удалось отклонить оплату.")
                    .parseMode(ParseMode.HTML)
                    .disablePreview()
            )
        }
}

private suspend fun handleAdminCommand(
    chatId: Long,
    fromId: Long,
    text: String,
    deps: AdminWebhookDeps,
    reply: (String) -> Unit
) {
    val (command, args) = splitCommand(text)
    when (command) {
        "/start" -> reply(START_REPLY)
        "/help" -> reply(HELP_REPLY)
        "/cancel" -> {
            deps.paymentDetailsStateStore.clear(fromId)
            deps.paymentRejectReasonStateStore.clear(fromId)
            reply("Действие отменено.")
        }
        "/new" -> handleCreateDraft(chatId, args, deps.clients, deps.itemsService)
        "/media" -> handleMediaStart(fromId, chatId, args, deps.mediaStateStore, reply)
        "/media_done" -> handleMediaFinalize(fromId, deps.mediaStateStore, deps.itemMediaRepository, reply)
        "/media_cancel" -> handleMediaCancel(fromId, deps.mediaStateStore, reply)
        "/preview" -> handlePreview(chatId, args, deps.itemMediaRepository, deps.clients, reply)
        "/post" -> handlePost(args, deps.postService, reply)
        STATUS_COMMAND -> handleStatusCommand(args, fromId, deps.orderStatusService, reply)
        COUNTER_COMMAND -> handleCounterCommand(args, fromId, deps.offersService, reply)
        STOCK_COMMAND -> handleStockCommand(args, deps.inventoryService, reply)
        ORDER_COMMAND -> handleOrderCommand(args, deps.ordersRepository, deps.orderDeliveryRepository, reply)
        else -> reply("Неизвестная команда. Напишите <code>/help</code>.")
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun handlePost(
    args: String,
    postService: PostService,
    reply: (String) -> Unit
) {
    val itemId = args.ifBlank { null }
    if (itemId == null) {
        reply("Укажите ID: <code>/post &lt;ITEM_ID&gt;</code>")
        return
    }

    try {
        val messageIds = postService.postItemAlbumToChannel(itemId)
        reply("✅ Опубликовано в канал. Сообщений в альбоме: ${messageIds.size}. Первая запись с CTA готова.")
    } catch (error: IllegalArgumentException) {
        val reason = error.message ?: "Некорректные данные"
        reply("⚠️ $reason")
    } catch (error: IllegalStateException) {
        val reason = error.message ?: "Состояние не позволяет опубликовать"
        reply("⚠️ $reason")
    } catch (error: Exception) {
        val reason = error.message ?: "Неизвестная ошибка"
        reply("❌ Не удалось опубликовать: $reason")
    }
}

private fun handleMediaStart(
    adminId: Long,
    chatId: Long,
    args: String,
    mediaStateStore: MediaStateStore,
    reply: (String) -> Unit
) {
    val itemId = args.ifBlank { null }
    if (itemId == null) {
        reply("Укажите ID: <code>/media &lt;ITEM_ID&gt;</code>")
        return
    }
    mediaStateStore.start(adminId, chatId, itemId)
    reply(
        """
        Режим сбора медиа для <code>$itemId</code>.
        Пришлите 2–10 фото/видео (одним альбомом или по одному).
        Когда закончите — команда <code>/media_done</code>.
        """.trimIndent()
    )
}

private suspend fun handleMediaFinalize(
    adminId: Long,
    mediaStateStore: MediaStateStore,
    itemMediaRepository: ItemMediaRepository,
    reply: (String) -> Unit
) {
    val state = mediaStateStore.get(adminId)
    if (state == null) {
        reply("Нет активного сбора. Используйте <code>/media &lt;ITEM_ID&gt;</code>.")
        return
    }
    val count = state.media.size
    if (count < 2 || count > 10) {
        reply("Нужно 2–10 медиа, сейчас: $count.")
        return
    }

    itemMediaRepository.deleteByItem(state.itemId)
    state.media.forEachIndexed { index, media ->
        itemMediaRepository.add(
            ItemMedia(
                id = 0,
                itemId = state.itemId,
                fileId = media.fileId,
                mediaType = media.type.name.lowercase(),
                sortOrder = index
            )
        )
    }
    reply("✅ Сохранено $count медиа для <code>${state.itemId}</code>.")
    mediaStateStore.clear(adminId)
}

private fun handleMediaCancel(
    adminId: Long,
    mediaStateStore: MediaStateStore,
    reply: (String) -> Unit
) {
    mediaStateStore.clear(adminId)
    reply("Отменено. Состояние сбора очищено.")
}

@Suppress("SpreadOperator")
private suspend fun handlePreview(
    chatId: Long,
    args: String,
    itemMediaRepository: ItemMediaRepository,
    clients: TelegramClients,
    reply: (String) -> Unit
) {
    val itemId = args.ifBlank { null }
    if (itemId == null) {
        reply("Укажите ID: <code>/preview &lt;ITEM_ID&gt;</code>")
        return
    }

    val medias = itemMediaRepository.listByItem(itemId)
    if (medias.isEmpty()) {
        reply(
            """
            Для <code>$itemId</code> нет сохранённых медиа.
            Используйте <code>/media $itemId</code> → <code>/media_done</code>.
            """.trimIndent()
        )
        return
    }

    val group: List<InputMedia<*>> = medias.map {
        when (it.mediaType) {
            "photo" -> InputMediaPhoto(it.fileId)
            "video" -> InputMediaVideo(it.fileId)
            else -> InputMediaPhoto(it.fileId)
        }
    }
    clients.adminBot.execute(
        SendMediaGroup(chatId, *group.toTypedArray())
    )
    reply("Предпросмотр отправлен альбомом.")
}

private fun handleMediaCollection(
    adminId: Long,
    chatId: Long,
    message: TgMessage,
    deps: AdminWebhookDeps,
    reply: (String) -> Unit
): Boolean {
    val state = deps.mediaStateStore.get(adminId)
    if (state == null || state.chatId != chatId) {
        return false
    }

    val added = mutableListOf<PendingMedia>()
    message.photo?.lastOrNull()?.let { added.add(PendingMedia(it.fileId, MediaType.PHOTO)) }
    message.video?.let { added.add(PendingMedia(it.fileId, MediaType.VIDEO)) }

    if (added.isNotEmpty()) {
        added.forEach { deps.mediaStateStore.add(adminId, it) }
        var total = state.media.size
        if (total > 10) {
            state.media.subList(10, total).clear()
            total = 10
            reply("Лимит 10 медиа. Лишние игнорированы. Сейчас: 10. Завершите /media_done.")
        } else {
            reply("Добавлено: ${added.size}. Сейчас: $total (нужно 2–10). Завершите /media_done.")
        }
    }

    return true
}

package com.example.miniapp

import com.example.miniapp.api.AdminChannelBindingRequest
import com.example.miniapp.api.AdminDeliveryMethodUpdateRequest
import com.example.miniapp.api.AdminOrderCardResponse
import com.example.miniapp.api.AdminOrderStatusRequest
import com.example.miniapp.api.AdminOrderSummary
import com.example.miniapp.api.AdminPaymentMethodDto
import com.example.miniapp.api.AdminPaymentMethodUpdate
import com.example.miniapp.api.AdminPaymentMethodsUpdateRequest
import com.example.miniapp.api.AdminPublishRequest
import com.example.miniapp.api.AdminStorefrontRequest
import com.example.miniapp.api.ApiClient
import io.kvision.Application
import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.form.text.TextInput
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.Link
import io.kvision.panel.HPanel
import io.kvision.panel.VPanel
import io.kvision.panel.root
import io.kvision.panel.vPanel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.max

private const val ADMIN_PAGE_LIMIT = 25

class AdminApp : Application() {
    private val scope = MainScope()
    private val api = ApiClient()

    private var currentBucket = "awaiting_payment"
    private var currentOffset = 0
    private var currentOrderId: String? = null
    private var isOwner = false

    override fun start(state: Map<String, Any>) {
        root("kvapp") {
            vPanel(spacing = 10) {
                val title = Div(className = "h1").apply { content = "Seller Admin" }
                val roleInfo = Div(className = "muted")
                val statusOk = Div(className = "ok")
                val statusErr = Div(className = "err")
                add(title)
                add(roleInfo)
                add(statusOk)
                add(statusErr)

                val ordersPanel = VPanel(spacing = 6)
                val orderCardPanel = VPanel(spacing = 6)
                val settingsPanel = VPanel(spacing = 6)
                val publishPanel = VPanel(spacing = 6)

                add(Div(className = "h2").apply { content = "Заказы" })
                add(ordersPanel)
                add(Div(className = "h2").apply { content = "Карточка заказа" })
                add(orderCardPanel)
                add(Div(className = "h2").apply { content = "Настройки" })
                add(settingsPanel)
                add(Div(className = "h2").apply { content = "Публикации" })
                add(publishPanel)

                val bucketsBar = HPanel(spacing = 6)
                val ordersList = VPanel(spacing = 4)
                val pager = HPanel(spacing = 6)

                ordersPanel.add(bucketsBar)
                ordersPanel.add(ordersList)
                ordersPanel.add(pager)

                val orderInfo = Div()
                val orderActions = VPanel(spacing = 6)
                orderCardPanel.add(orderInfo)
                orderCardPanel.add(orderActions)

                val paymentMethodsPanel = VPanel(spacing = 6)
                val deliveryPanel = VPanel(spacing = 6)
                val storefrontsPanel = VPanel(spacing = 6)
                val bindingsPanel = VPanel(spacing = 6)
                settingsPanel.add(paymentMethodsPanel)
                settingsPanel.add(deliveryPanel)
                settingsPanel.add(storefrontsPanel)
                settingsPanel.add(bindingsPanel)

                val publishForm = VPanel(spacing = 6)
                publishPanel.add(publishForm)

                fun showError(msg: String) {
                    statusErr.content = msg
                    statusOk.content = ""
                }

                fun showOk(msg: String) {
                    statusOk.content = msg
                    statusErr.content = ""
                }

                fun renderOrders(items: List<AdminOrderSummary>) {
                    ordersList.removeAll()
                    if (items.isEmpty()) {
                        ordersList.add(Div("Нет заказов"))
                        return
                    }
                    items.forEach { order ->
                        val line = Button("${order.orderId} • ${order.status} • ${order.amountMinor} ${order.currency}")
                        line.onClick {
                            loadOrder(order.orderId, orderInfo, orderActions, ::showOk, ::showError)
                        }
                        ordersList.add(line)
                    }
                }

                fun loadOrders() {
                    scope.launch {
                        runCatching {
                            api.listAdminOrders(currentBucket, ADMIN_PAGE_LIMIT, currentOffset)
                        }.onSuccess { page ->
                            renderOrders(page.items)
                        }.onFailure { e ->
                            showError("Ошибка загрузки заказов: ${e.message}")
                        }
                    }
                }

                listOf(
                    "awaiting_payment" to "Ожидает оплаты",
                    "under_review" to "На проверке",
                    "paid" to "Оплачено",
                    "shipped" to "Отгружено"
                ).forEach { (bucket, label) ->
                    val btn = Button(label)
                    btn.onClick {
                        currentBucket = bucket
                        currentOffset = 0
                        loadOrders()
                    }
                    bucketsBar.add(btn)
                }

                val prevBtn = Button("Назад")
                val nextBtn = Button("Вперед")
                prevBtn.onClick {
                    currentOffset = max(0, currentOffset - ADMIN_PAGE_LIMIT)
                    loadOrders()
                }
                nextBtn.onClick {
                    currentOffset += ADMIN_PAGE_LIMIT
                    loadOrders()
                }
                pager.add(prevBtn)
                pager.add(nextBtn)

                fun loadSettings() {
                    loadPaymentMethods(paymentMethodsPanel, ::showOk, ::showError)
                    loadDeliveryMethod(deliveryPanel, ::showOk, ::showError)
                    loadStorefronts(storefrontsPanel, ::showOk, ::showError)
                    loadChannelBindings(bindingsPanel, ::showOk, ::showError)
                }

                fun loadPublishForm() {
                    publishForm.removeAll()
                    val itemIdInput = TextInput().apply { placeholder = "itemId" }
                    val channelIdsInput = TextInput().apply { placeholder = "channelIds через запятую" }
                    val publishButton = Button("Опубликовать")
                    publishButton.onClick {
                        scope.launch {
                            val channelIds = channelIdsInput.value?.split(",")
                                ?.mapNotNull { it.trim().toLongOrNull() }
                                .orEmpty()
                            runCatching {
                                api.publish(AdminPublishRequest(itemIdInput.value ?: "", channelIds))
                            }.onSuccess { resp ->
                                val okCount = resp.results.count { it.ok }
                                showOk("Опубликовано: $okCount / ${resp.results.size}")
                            }.onFailure { e ->
                                showError("Ошибка публикации: ${e.message}")
                            }
                        }
                    }
                    publishButton.disabled = !isOwner
                    publishForm.add(itemIdInput)
                    publishForm.add(channelIdsInput)
                    publishForm.add(publishButton)
                }

                scope.launch {
                    runCatching { api.getAdminMe() }
                        .onSuccess { me ->
                            roleInfo.content = "Пользователь: ${me.userId} • Роль: ${me.role}"
                            isOwner = me.role == "OWNER"
                            loadOrders()
                            loadSettings()
                            loadPublishForm()
                        }
                        .onFailure { e ->
                            showError("Ошибка авторизации: ${e.message}")
                        }
                }
            }
        }
    }

    private fun loadOrder(
        orderId: String,
        orderInfo: Div,
        orderActions: VPanel,
        showOk: (String) -> Unit,
        showError: (String) -> Unit
    ) {
        currentOrderId = orderId
        scope.launch {
            runCatching { api.getAdminOrder(orderId) }
                .onSuccess { order ->
                    renderOrder(order, orderInfo, orderActions, showOk, showError)
                }
                .onFailure { e ->
                    showError("Ошибка загрузки заказа: ${e.message}")
                }
        }
    }

    private fun renderOrder(
        order: AdminOrderCardResponse,
        orderInfo: Div,
        orderActions: VPanel,
        showOk: (String) -> Unit,
        showError: (String) -> Unit
    ) {
        orderInfo.content = ""
        orderInfo.add(Div("Статус: ${order.status}"))
        orderInfo.add(Div("Сумма: ${order.amountMinor} ${order.currency}"))
        orderInfo.add(Div("Buyer: ${order.buyerId}"))
        orderInfo.add(Div("Обновлено: ${order.updatedAt}"))
        order.delivery?.let { delivery ->
            orderInfo.add(Div("Доставка: ${delivery.type}"))
        }

        orderActions.removeAll()
        val rejectReason = TextInput().apply { placeholder = "Причина отклонения" }
        val statusInput = TextInput().apply { placeholder = "Новый статус"; value = order.status }
        val statusComment = TextInput().apply { placeholder = "Комментарий" }
        val trackingInput = TextInput().apply { placeholder = "Tracking" }

        val confirmBtn = Button("Подтвердить оплату")
        val rejectBtn = Button("Отклонить оплату")
        val statusBtn = Button("Обновить статус")

        confirmBtn.onClick {
            scope.launch {
                runCatching { api.confirmPayment(order.orderId) }
                    .onSuccess { showOk("Оплата подтверждена") }
                    .onFailure { e -> showError("Ошибка подтверждения: ${e.message}") }
            }
        }
        rejectBtn.onClick {
            scope.launch {
                runCatching { api.rejectPayment(order.orderId, rejectReason.value ?: "") }
                    .onSuccess { showOk("Платеж отклонен") }
                    .onFailure { e -> showError("Ошибка отклонения: ${e.message}") }
            }
        }
        statusBtn.onClick {
            scope.launch {
                runCatching {
                    api.updateOrderStatus(
                        order.orderId,
                        AdminOrderStatusRequest(
                            status = statusInput.value ?: order.status,
                            comment = statusComment.value,
                            trackingCode = trackingInput.value
                        )
                    )
                }.onSuccess { showOk("Статус обновлен") }
                    .onFailure { e -> showError("Ошибка статуса: ${e.message}") }
            }
        }

        orderActions.add(confirmBtn)
        orderActions.add(rejectReason)
        orderActions.add(rejectBtn)
        orderActions.add(statusInput)
        orderActions.add(statusComment)
        orderActions.add(trackingInput)
        orderActions.add(statusBtn)

        order.payment?.attachments?.let { attachments ->
            if (attachments.isNotEmpty()) {
                orderActions.add(Div("Чеки оплаты:"))
                attachments.forEach { attachment ->
                    val link = Link("attachment-${attachment.id}", attachment.presignedUrl, target = "_blank")
                    orderActions.add(link)
                }
            }
        }
    }

    private fun loadPaymentMethods(
        container: VPanel,
        showOk: (String) -> Unit,
        showError: (String) -> Unit
    ) {
        container.removeAll()
        container.add(Div("Payment methods"))
        scope.launch {
            runCatching { api.getPaymentMethods() }
                .onSuccess { methods ->
                    renderPaymentMethods(container, methods, showOk, showError)
                }
                .onFailure { e -> showError("Ошибка payment methods: ${e.message}") }
        }
    }

    private fun renderPaymentMethods(
        container: VPanel,
        methods: List<AdminPaymentMethodDto>,
        showOk: (String) -> Unit,
        showError: (String) -> Unit
    ) {
        val rows = methods.map { method ->
            val enabled = CheckBox(label = method.type).apply { value = method.enabled }
            val mode = TextInput().apply { value = method.mode }
            val details = TextInput().apply { value = method.details ?: ""; placeholder = "details" }
            if (!isOwner) {
                enabled.disabled = true
                mode.disabled = true
                details.disabled = true
            }
            container.add(enabled)
            container.add(mode)
            container.add(details)
            Triple(method.type, enabled, Pair(mode, details))
        }
        val save = Button("Сохранить методы")
        save.disabled = !isOwner
        save.onClick {
            val updates = rows.map { (type, enabled, inputs) ->
                val (mode, details) = inputs
                AdminPaymentMethodUpdate(
                    type = type,
                    mode = mode.value ?: "MANUAL_SEND",
                    enabled = enabled.value,
                    details = details.value
                )
            }
            scope.launch {
                runCatching { api.updatePaymentMethods(AdminPaymentMethodsUpdateRequest(updates)) }
                    .onSuccess { showOk("Методы сохранены") }
                    .onFailure { e -> showError("Ошибка сохранения: ${e.message}") }
            }
        }
        container.add(save)
    }

    private fun loadDeliveryMethod(
        container: VPanel,
        showOk: (String) -> Unit,
        showError: (String) -> Unit
    ) {
        container.removeAll()
        container.add(Div("Delivery method"))
        scope.launch {
            runCatching { api.getDeliveryMethod() }
                .onSuccess { method ->
                    val enabled = CheckBox(label = "enabled").apply { value = method.enabled }
                    val fields = TextInput().apply {
                        value = method.requiredFields.joinToString(",")
                    }
                    if (!isOwner) {
                        enabled.disabled = true
                        fields.disabled = true
                    }
                    val save = Button("Сохранить доставку")
                    save.disabled = !isOwner
                    save.onClick {
                        scope.launch {
                            val required = fields.value?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                                .orEmpty()
                            runCatching {
                                api.updateDeliveryMethod(
                                    AdminDeliveryMethodUpdateRequest(
                                        enabled = enabled.value,
                                        requiredFields = required
                                    )
                                )
                            }.onSuccess { showOk("Доставка сохранена") }
                                .onFailure { e -> showError("Ошибка доставки: ${e.message}") }
                        }
                    }
                    container.add(enabled)
                    container.add(fields)
                    container.add(save)
                }
                .onFailure { e -> showError("Ошибка доставки: ${e.message}") }
        }
    }

    private fun loadStorefronts(
        container: VPanel,
        showOk: (String) -> Unit,
        showError: (String) -> Unit
    ) {
        container.removeAll()
        container.add(Div("Storefronts"))
        scope.launch {
            runCatching { api.getStorefronts() }
                .onSuccess { storefronts ->
                    storefronts.forEach { sf ->
                        container.add(Div("${sf.id} • ${sf.name}"))
                    }
                    val idInput = TextInput().apply { placeholder = "storefront id" }
                    val nameInput = TextInput().apply { placeholder = "storefront name" }
                    val save = Button("Добавить/обновить витрину")
                    save.disabled = !isOwner
                    save.onClick {
                        scope.launch {
                            runCatching {
                                api.upsertStorefront(AdminStorefrontRequest(idInput.value ?: "", nameInput.value ?: ""))
                            }.onSuccess { showOk("Витрина сохранена") }
                                .onFailure { e -> showError("Ошибка витрины: ${e.message}") }
                        }
                    }
                    if (!isOwner) {
                        idInput.disabled = true
                        nameInput.disabled = true
                    }
                    container.add(idInput)
                    container.add(nameInput)
                    container.add(save)
                }
                .onFailure { e -> showError("Ошибка витрин: ${e.message}") }
        }
    }

    private fun loadChannelBindings(
        container: VPanel,
        showOk: (String) -> Unit,
        showError: (String) -> Unit
    ) {
        container.removeAll()
        container.add(Div("Channel bindings"))
        scope.launch {
            runCatching { api.getChannelBindings() }
                .onSuccess { bindings ->
                    bindings.forEach { binding ->
                        container.add(Div("${binding.channelId} -> ${binding.storefrontId}"))
                    }
                    val storefrontIdInput = TextInput().apply { placeholder = "storefront id" }
                    val channelIdInput = TextInput().apply { placeholder = "channel id" }
                    val save = Button("Добавить/обновить канал")
                    save.disabled = !isOwner
                    save.onClick {
                        scope.launch {
                            val channelId = channelIdInput.value?.toLongOrNull() ?: 0L
                            runCatching {
                                api.upsertChannelBinding(
                                    AdminChannelBindingRequest(
                                        storefrontId = storefrontIdInput.value ?: "",
                                        channelId = channelId
                                    )
                                )
                            }.onSuccess { showOk("Канал сохранен") }
                                .onFailure { e -> showError("Ошибка канала: ${e.message}") }
                        }
                    }
                    if (!isOwner) {
                        storefrontIdInput.disabled = true
                        channelIdInput.disabled = true
                    }
                    container.add(storefrontIdInput)
                    container.add(channelIdInput)
                    container.add(save)
                }
                .onFailure { e -> showError("Ошибка каналов: ${e.message}") }
        }
    }
}

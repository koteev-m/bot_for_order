package com.example.miniapp

import com.example.miniapp.api.AddByTokenResponse
import com.example.miniapp.api.ApiClient
import com.example.miniapp.api.ApiClientException
import com.example.miniapp.api.ItemResponse
import com.example.miniapp.api.LinkResolveVariant
import com.example.miniapp.api.OfferAcceptRequest
import com.example.miniapp.api.OfferRequest
import com.example.miniapp.api.OrderCreateRequest
import com.example.miniapp.api.VariantRequiredResult
import com.example.miniapp.api.WatchlistSubscribeRequest
import com.example.miniapp.cart.MoneySubtotal
import com.example.miniapp.cart.buildCartTotal
import com.example.miniapp.cart.groupCartLinesByStorefront
import com.example.miniapp.quickadd.QuickAddStateKind
import com.example.miniapp.quickadd.evaluateQuickAddState
import com.example.miniapp.startapp.StartAppCodecJs
import com.example.miniapp.tg.TelegramBridge
import com.example.miniapp.tg.UrlQuery
import io.kvision.Application
import io.kvision.BootstrapCssModule
import io.kvision.BootstrapModule
import io.kvision.CoreModule
import io.kvision.Hot
import io.kvision.TomSelectModule
import io.kvision.core.onClick
import io.kvision.form.select.TomSelect
import io.kvision.form.text.TextArea
import io.kvision.form.text.TextInput
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.InputType
import io.kvision.panel.root
import io.kvision.panel.vPanel
import io.kvision.startApplication
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max

private const val DEFAULT_QUANTITY = 1
private const val MINOR_UNITS_IN_MAJOR = 100
private const val MINOR_DIGITS = 2
private const val DEFAULT_CURRENCY = "RUB"
private const val UNDO_TIMEOUT_MS = 7_000L
private const val SCREEN_CART = "cart"
private const val SCREEN_CHECKOUT = "checkout"

class MiniApp : Application() {
    private val appJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + appJob)
    private val api = ApiClient()
    private var currentItem: ItemResponse? = null
    private var quickAddRequestJob: Job? = null
    private var undoHideJob: Job? = null
    private var activeUndoToken: String? = null
    private var activeUndoLineId: Long? = null
    private var checkoutIdempotencyKey: String? = null

    override fun dispose(): Map<String, Any> {
        quickAddRequestJob?.cancel()
        undoHideJob?.cancel()
        scope.cancel()
        return super.dispose()
    }

    override fun start(state: Map<String, Any>) {
        val qp = UrlQuery.parse(window.location.search)
        val startParam = TelegramBridge.startParam()?.takeIf { it.isNotBlank() }
        val requestedScreen = resolveScreen(startParam, qp["screen"])
        if (requestedScreen == SCREEN_CART) {
            renderCartScreen()
            return
        }
        if (requestedScreen == SCREEN_CHECKOUT) {
            renderCheckoutScreen()
            return
        }
        val startParamItemId = startParam?.let { decodeStartParamItemId(it) }
        val quickAddToken = qp["token"] ?: startParam?.takeIf { startParamItemId == null && it != SCREEN_CART }
        if (!quickAddToken.isNullOrBlank()) {
            renderQuickAdd(quickAddToken, qp)
            return
        }

        root("kvapp") {
            vPanel(spacing = 8) {
                val titleEl = Div(className = "h1")
                val descEl = Div()
                val priceEl = Div()
                val infoEl = Div(className = "muted")

                val variantSelect = TomSelect(options = listOf())
                val qtyInput = TextInput(InputType.NUMBER).apply {
                    placeholder = "Кол-во"
                    value = DEFAULT_QUANTITY.toString()
                }
                val nameInput = TextInput(InputType.TEXT).apply { placeholder = "Ваше имя" }
                val phoneInput = TextInput(InputType.TEL).apply { placeholder = "Телефон" }
                val addrInput = TextArea(rows = 3).apply { placeholder = "Адрес доставки (если требуется)" }
                val targetInput = TextInput(InputType.TEXT).apply {
                    placeholder = "Целевая цена (опционально)"
                }

                val statusOk = Div(className = "ok")
                val statusErr = Div(className = "err")

                add(Div(className = "card").apply {
                    add(Div(className = "buttons").apply {
                        add(
                            Button("Корзина", className = "secondary").apply {
                                onClick { navigateToScreen(SCREEN_CART) }
                            }
                        )
                    })
                    add(titleEl)
                    add(descEl)
                    add(priceEl)
                    add(infoEl)
                    add(Div(className = "h2").apply {
                        content = "Параметры"
                    })
                    add(Div(className = "row").apply {
                        add(variantSelect)
                        add(Div("Кол-во:"))
                        add(qtyInput)
                    })
                    add(Div(className = "h2").apply {
                        content = "Контакты / адрес"
                    })
                    add(Div(className = "row").apply {
                        add(nameInput)
                        add(phoneInput)
                    })
                    add(addrInput)
                    add(Div(className = "buttons").apply {
                        add(
                            Button("Купить", className = "primary").apply {
                                onClick {
                                    onBuyClicked(
                                        qtyInput.value,
                                        variantSelect.value,
                                        nameInput.value,
                                        phoneInput.value,
                                        addrInput.value,
                                        statusOk,
                                        statusErr
                                    )
                                }
                            }
                        )
                        add(
                            Button("Предложить цену", className = "secondary").apply {
                                onClick {
                                    onOfferClicked(
                                        qtyInput.value,
                                        variantSelect.value,
                                        statusOk,
                                        statusErr
                                    )
                                }
                            }
                        )
                    })
                    add(Div(className = "watchlist").apply {
                        add(targetInput)
                        add(
                            Button("Сообщить при снижении цены", className = "secondary").apply {
                                onClick {
                                    onSubscribePriceDrop(
                                        targetInput.value,
                                        statusOk,
                                        statusErr
                                    )
                                }
                            }
                        )
                        add(
                            Button("Сообщить о наличии", className = "secondary").apply {
                                onClick {
                                    onSubscribeRestock(
                                        variantSelect.value,
                                        statusOk,
                                        statusErr
                                    )
                                }
                            }
                        )
                    })
                    add(statusOk)
                    add(statusErr)
                })

                val itemId = qp["item"] ?: startParamItemId
                if (itemId == null) {
                    titleEl.content = "Не указан товар"
                    descEl.content = "Откройте Mini App по кнопке «Купить» или передайте ?item=<ID>."
                } else {
                    loadItem(itemId, titleEl, descEl, priceEl, infoEl, variantSelect)
                }
                val offerIdParam = qp["offer"]
                val actionParam = qp["action"]
                if (offerIdParam != null && actionParam == "accept") {
                    val qty = qp["qty"]?.toIntOrNull()?.coerceAtLeast(DEFAULT_QUANTITY)
                        ?: DEFAULT_QUANTITY
                    acceptOfferFromQuery(offerIdParam, qty, statusOk, statusErr)
                }
            }
        }
    }

    private fun decodeStartParamItemId(startParam: String): String? =
        runCatching { StartAppCodecJs.decode(startParam).itemId }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun renderQuickAdd(token: String, qp: Map<String, String>) {
        root("kvapp") {
            vPanel(spacing = 8) {
                val titleEl = Div(className = "h1").apply { content = "Быстрое добавление" }
                val statusEl = Div(className = "muted").apply { content = "Проверяем ссылку…" }
                val chipsContainer = Div(className = "chips")
                val variantChipButtons = mutableListOf<Button>()
                val errorEl = Div(className = "err")
                val toastEl = Div(className = "snackbar")
                val undoButton = Button("Undo", className = "secondary").apply { visible = false }
                undoButton.onClick {
                    val lineId = activeUndoLineId
                    val undoToken = activeUndoToken
                    if (lineId == null || undoToken.isNullOrBlank()) {
                        errorEl.content = "Undo недоступен"
                        return@onClick
                    }
                    if (undoButton.disabled) {
                        return@onClick
                    }
                    undoButton.disabled = true
                    quickAddRequestJob?.cancel()
                    quickAddRequestJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            try {
                                api.removeCartLine(lineId)
                                clearUndoState(toastEl, undoButton)
                                toastEl.content = "Отменено"
                                return@launch
                            } catch (e: Throwable) {
                                if (e is CancellationException) {
                                    throw e
                                }
                            }
                            try {
                                api.undoAdd(undoToken)
                                clearUndoState(toastEl, undoButton)
                                toastEl.content = "Отменено"
                            } catch (e: Throwable) {
                                if (e is CancellationException) {
                                    throw e
                                }
                                errorEl.content = "Undo не выполнен: ${e.message ?: e.toString()}"
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } finally {
                            undoButton.disabled = false
                        }
                    }
                }
                add(Div(className = "card").apply {
                    add(titleEl)
                    add(statusEl)
                    add(chipsContainer)
                    add(errorEl)
                    add(toastEl)
                    add(undoButton)
                })

                quickAddRequestJob?.cancel()
                quickAddRequestJob = scope.launch {
                    runCatching { api.resolveLink(token) }
                        .onSuccess { resolved ->
                            titleEl.content = escape(resolved.listing.title)
                            val state = evaluateQuickAddState(resolved)
                            when (state.kind) {
                                QuickAddStateKind.AUTO_ADD -> {
                                    statusEl.content = "Добавляем в корзину…"
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        performQuickAdd(
                                            token,
                                            state.selectedVariantId,
                                            qp,
                                            statusEl,
                                            errorEl,
                                            toastEl,
                                            undoButton,
                                            variantChipButtons
                                        )
                                    }
                                }

                                QuickAddStateKind.NEED_VARIANT -> {
                                    statusEl.content = "Выберите вариант"
                                    renderVariantChips(
                                        chipsContainer,
                                        state.variants,
                                        errorEl,
                                        variantChipButtons
                                    ) { selected ->
                                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                            performQuickAdd(
                                                token,
                                                selected,
                                                qp,
                                                statusEl,
                                                errorEl,
                                                toastEl,
                                                undoButton,
                                                variantChipButtons
                                            )
                                        }
                                    }
                                }

                                QuickAddStateKind.ERROR -> {
                                    errorEl.content = state.errorMessage ?: "Не удалось обработать ссылку"
                                }
                            }
                        }
                        .onFailure { e ->
                            statusEl.content = ""
                            errorEl.content = "Ошибка resolve: ${e.message ?: e.toString()}"
                        }
                }
            }
        }
    }

    private fun renderVariantChips(
        container: Div,
        variants: List<LinkResolveVariant>,
        errorEl: Div,
        chipButtons: MutableList<Button>,
        onPick: (String) -> Unit
    ) {
        container.removeAll()
        chipButtons.clear()
        variants.forEach { variant ->
            val label = variant.size ?: variant.sku ?: variant.id
            val chip = Button(label, className = "chip").apply {
                disabled = !variant.available
                onClick {
                    if (!variant.available) {
                        errorEl.content = "Вариант недоступен"
                        return@onClick
                    }
                    onPick(variant.id)
                }
            }
            if (variant.available) {
                chipButtons += chip
            }
            container.add(chip)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun performQuickAdd(
        token: String,
        variantId: String?,
        qp: Map<String, String>,
        statusEl: Div,
        errorEl: Div,
        toastEl: Div,
        undoButton: Button,
        chipButtons: List<Button>
    ) {
        errorEl.content = ""
        val idempotencyKey = createIdempotencyKey()
        setVariantChipsEnabled(chipButtons, false)
        try {
            when (val response = api.addToCartByToken(token, variantId, idempotencyKey)) {
                is AddByTokenResponse -> {
                    statusEl.content = ""
                    TelegramBridge.hapticSuccess()
                    showUndoToast(toastEl, undoButton, response.undoToken, response.addedLineId)
                    maybeAutoClose(qp)
                }

                is VariantRequiredResult -> {
                    statusEl.content = "Выберите вариант"
                    errorEl.content = "Нужен выбор варианта"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            statusEl.content = ""
            errorEl.content = "Не удалось добавить в корзину: ${e.message ?: e.toString()}"
        } finally {
            setVariantChipsEnabled(chipButtons, true)
        }
    }

    private fun setVariantChipsEnabled(chipButtons: List<Button>, enabled: Boolean) {
        chipButtons.forEach { chip ->
            chip.disabled = !enabled
        }
    }

    private fun showUndoToast(
        toastEl: Div,
        undoButton: Button,
        undoToken: String,
        lineId: Long
    ) {
        activeUndoToken = undoToken
        activeUndoLineId = lineId
        toastEl.content = "Добавлено"
        undoButton.visible = true
        undoHideJob?.cancel()
        undoHideJob = scope.launch {
            delay(UNDO_TIMEOUT_MS)
            clearUndoState(toastEl, undoButton)
        }
    }

    private fun clearUndoState(toastEl: Div, undoButton: Button) {
        undoHideJob?.cancel()
        undoHideJob = null
        activeUndoToken = null
        activeUndoLineId = null
        undoButton.visible = false
        toastEl.content = ""
    }

    private fun maybeAutoClose(qp: Map<String, String>) {
        val compact = qp["mode"] == "compact"
        if (!compact) return
        if (!TelegramBridge.closeIfAvailable()) {
            window.history.back()
        }
    }

    private fun loadItem(
        itemId: String,
        titleEl: Div,
        descEl: Div,
        priceEl: Div,
        infoEl: Div,
        variantSelect: TomSelect
    ) {
        TelegramBridge.ready()
        scope.launch {
            runCatching { api.getItem(itemId) }
                .onSuccess { item ->
                    currentItem = item
                    renderItem(item, titleEl, descEl, priceEl, infoEl, variantSelect)
                }
                .onFailure { e ->
                    descEl.content = "Ошибка загрузки товара: ${e.message ?: e.toString()}"
                }
        }
    }

    private fun renderItem(
        item: ItemResponse,
        titleEl: Div,
        descEl: Div,
        priceEl: Div,
        infoEl: Div,
        variantSelect: TomSelect
    ) {
        titleEl.content = escape(item.title)
        descEl.content = escape(item.description)
        priceEl.content = buildPriceLine(item)
        infoEl.content = "ID: ${item.id}"
        val options = item.variants
            .filter { it.active }
            .map { it.id to (it.size ?: it.sku ?: it.id) }
        variantSelect.options = options
    }

    private fun acceptOfferFromQuery(offerId: String, qty: Int, ok: Div, err: Div) {
        ok.content = "Обрабатываем контр-офер..."
        err.content = ""
        scope.launch {
            runCatching {
                api.acceptOffer(
                    OfferAcceptRequest(
                        offerId = offerId,
                        qty = qty
                    )
                )
            }.onSuccess { resp ->
                ok.content = "Контр-офер принят: заказ ${resp.orderId} (статус ${resp.status})."
            }.onFailure { e ->
                err.content = "Не удалось принять офер: ${e.message ?: e.toString()}"
            }
        }
    }

    private fun renderCheckoutScreen() {
        root("kvapp") {
            vPanel(spacing = 8) {
                val titleEl = Div(className = "h1").apply { content = "Оформление заказа" }
                val statusEl = Div(className = "muted").apply { content = "Загружаем корзину…" }
                val errorEl = Div(className = "err")
                val reviewEl = Div()
                val totalsEl = Div(className = "muted")
                val deliveryNameInput = TextInput(InputType.TEXT).apply { placeholder = "Имя получателя" }
                val deliveryPhoneInput = TextInput(InputType.TEL).apply { placeholder = "Телефон" }
                val deliveryAddressInput = TextArea(rows = 3).apply { placeholder = "Адрес доставки" }
                val submitButton = Button("Подтвердить заказ", className = "primary")
                val backButton = Button("Назад в корзину", className = "secondary").apply {
                    onClick { navigateToScreen(SCREEN_CART) }
                }

                add(Div(className = "card").apply {
                    add(titleEl)
                    add(statusEl)
                    add(errorEl)
                    add(reviewEl)
                    add(totalsEl)
                    add(Div(className = "h2").apply { content = "Доставка" })
                    add(Div(className = "row").apply {
                        add(deliveryNameInput)
                        add(deliveryPhoneInput)
                    })
                    add(deliveryAddressInput)
                    add(Div(className = "buttons").apply {
                        add(submitButton)
                        add(backButton)
                    })
                })

                suspend fun refreshCheckout() {
                    statusEl.content = "Загружаем корзину…"
                    errorEl.content = ""
                    reviewEl.removeAll()
                    totalsEl.content = ""
                    checkoutIdempotencyKey = null
                    try {
                        val cart = api.getCart().cart
                        statusEl.content = ""
                        if (cart.items.isEmpty()) {
                            reviewEl.add(Div(className = "muted").apply { content = "Корзина пуста. Добавьте товары перед оформлением." })
                            submitButton.disabled = true
                            return
                        }
                        submitButton.disabled = false
                        cart.items.forEach { line ->
                            val variant = line.variantId?.let { " · вариант ${escape(it)}" }.orEmpty()
                            val amount = formatMoney(line.priceSnapshotMinor * line.qty, line.currency)
                            reviewEl.add(Div(className = "card").apply {
                                content = "${escape(line.listingId)}$variant · ${line.qty} шт. · $amount"
                            })
                        }
                        totalsEl.content = "Итого к оплате: ${formatSubtotals(buildCartTotal(cart.items))}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        statusEl.content = ""
                        submitButton.disabled = true
                        errorEl.content = "Не удалось загрузить checkout: ${humanizeCartError(e)}"
                    }
                }

                submitButton.onClick {
                    if (submitButton.disabled) return@onClick
                    submitButton.disabled = true
                    errorEl.content = ""
                    statusEl.content = "Создаём заказ…"
                    scope.launch {
                        try {
                            val idempotencyKey = checkoutIdempotencyKey ?: createIdempotencyKey().also {
                                checkoutIdempotencyKey = it
                            }
                            val created = api.createOrderFromCart(idempotencyKey)
                            val hasDelivery = !deliveryNameInput.value.isNullOrBlank() ||
                                !deliveryPhoneInput.value.isNullOrBlank() ||
                                !deliveryAddressInput.value.isNullOrBlank()
                            if (hasDelivery) {
                                val fields = buildJsonObject {
                                    deliveryNameInput.value?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                                    deliveryPhoneInput.value?.takeIf { it.isNotBlank() }?.let { put("phone", it) }
                                    deliveryAddressInput.value?.takeIf { it.isNotBlank() }?.let { put("address", it) }
                                }
                                api.setOrderDelivery(created.orderId, com.example.miniapp.api.OrderDeliveryRequest(fields))
                            }
                            checkoutIdempotencyKey = null
                            statusEl.content = ""
                            TelegramBridge.hapticSuccess()
                            reviewEl.removeAll()
                            totalsEl.content = ""
                            reviewEl.add(Div(className = "ok").apply {
                                content = "Заказ ${created.orderId} создан (статус ${created.status})."
                            })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            statusEl.content = ""
                            errorEl.content = humanizeCheckoutSubmitError(e)
                        } finally {
                            submitButton.disabled = false
                        }
                    }
                }

                scope.launch {
                    refreshCheckout()
                }
            }
        }
    }

    private fun renderCartScreen() {
        root("kvapp") {
            vPanel(spacing = 8) {
                val titleEl = Div(className = "h1").apply { content = "Корзина" }
                val statusEl = Div(className = "muted").apply { content = "Загружаем корзину…" }
                val errorEl = Div(className = "err")
                val groupsEl = Div()
                val totalsEl = Div(className = "muted")
                val checkoutButton = Button("Перейти к оформлению", className = "primary").apply {
                    onClick { navigateToScreen(SCREEN_CHECKOUT) }
                }
                val backButton = Button("Вернуться к витрине", className = "secondary").apply {
                    onClick { navigateToScreen(null) }
                }

                add(Div(className = "card").apply {
                    add(titleEl)
                    add(statusEl)
                    add(errorEl)
                    add(groupsEl)
                    add(totalsEl)
                    add(Div(className = "buttons").apply {
                        add(checkoutButton)
                        add(backButton)
                    })
                })

                scope.launch {
                    loadAndRenderCart(groupsEl, totalsEl, statusEl, errorEl)
                }
            }
        }
    }

    private suspend fun loadAndRenderCart(
        groupsEl: Div,
        totalsEl: Div,
        statusEl: Div,
        errorEl: Div
    ) {
        statusEl.content = "Загружаем корзину…"
        errorEl.content = ""
        groupsEl.removeAll()
        totalsEl.content = ""
        try {
            val cart = api.getCart().cart
            statusEl.content = ""
            if (cart.items.isEmpty()) {
                groupsEl.add(Div(className = "muted").apply {
                    content = "Корзина пока пустая"
                })
                return
            }
            val grouped = groupCartLinesByStorefront(cart.items)
            grouped.forEach { group ->
                groupsEl.add(Div(className = "h2").apply {
                    content = "Витрина: ${escape(group.storefrontId)}"
                })
                group.lines.forEach { line ->
                    groupsEl.add(Div(className = "card").apply {
                        val rowSummary = Div().apply {
                            val variant = line.variantId?.let { " · вариант ${escape(it)}" }.orEmpty()
                            val lineTotal = formatMoney(line.priceSnapshotMinor * line.qty, line.currency)
                            content = "${escape(line.listingId)}$variant · $lineTotal"
                        }
                        val qtyRow = Div(className = "buttons")
                        val qtyLabel = Div().apply { content = "Кол-во: ${line.qty}" }
                        val minus = Button("-", className = "secondary")
                        val plus = Button("+", className = "secondary")
                        val remove = Button("Удалить", className = "secondary")
                        fun setDisabled(disabled: Boolean) {
                            minus.disabled = disabled
                            plus.disabled = disabled
                            remove.disabled = disabled
                        }
                        minus.onClick {
                            if (minus.disabled || plus.disabled || remove.disabled) return@onClick
                            if (line.qty <= DEFAULT_QUANTITY) return@onClick
                            setDisabled(true)
                            scope.launch {
                                var mutationSucceeded = false
                                try {
                                    mutationSucceeded = handleCartMutation(errorEl) {
                                        api.updateCartQty(line.lineId, line.qty - 1)
                                    }
                                    if (mutationSucceeded) {
                                        loadAndRenderCart(groupsEl, totalsEl, statusEl, errorEl)
                                    }
                                } finally {
                                    if (!mutationSucceeded) {
                                        setDisabled(false)
                                    }
                                }
                            }
                        }
                        plus.onClick {
                            if (minus.disabled || plus.disabled || remove.disabled) return@onClick
                            setDisabled(true)
                            scope.launch {
                                var mutationSucceeded = false
                                try {
                                    mutationSucceeded = handleCartMutation(errorEl) {
                                        api.updateCartQty(line.lineId, line.qty + 1)
                                    }
                                    if (mutationSucceeded) {
                                        loadAndRenderCart(groupsEl, totalsEl, statusEl, errorEl)
                                    }
                                } finally {
                                    if (!mutationSucceeded) {
                                        setDisabled(false)
                                    }
                                }
                            }
                        }
                        remove.onClick {
                            if (minus.disabled || plus.disabled || remove.disabled) return@onClick
                            setDisabled(true)
                            scope.launch {
                                var mutationSucceeded = false
                                try {
                                    mutationSucceeded = handleCartMutation(errorEl) {
                                        api.removeCartLineFromScreen(line.lineId)
                                    }
                                    if (mutationSucceeded) {
                                        loadAndRenderCart(groupsEl, totalsEl, statusEl, errorEl)
                                    }
                                } finally {
                                    if (!mutationSucceeded) {
                                        setDisabled(false)
                                    }
                                }
                            }
                        }
                        qtyRow.add(qtyLabel)
                        qtyRow.add(minus)
                        qtyRow.add(plus)
                        qtyRow.add(remove)
                        add(rowSummary)
                        add(qtyRow)
                    })
                }
                groupsEl.add(
                    Div(className = "muted").apply {
                        content = "Подытог витрины: ${formatSubtotals(group.subtotals)}"
                    }
                )
            }
            totalsEl.content = "Итого: ${formatSubtotals(buildCartTotal(cart.items))}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            statusEl.content = ""
            errorEl.content = "Не удалось загрузить корзину: ${humanizeCartError(e)}"
        }
    }

    private suspend fun handleCartMutation(errorEl: Div, action: suspend () -> Unit): Boolean {
        errorEl.content = ""
        return try {
            action()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorEl.content = humanizeCartError(e)
            false
        }
    }

    private fun formatSubtotals(subtotals: List<MoneySubtotal>): String {
        return subtotals.joinToString(" / ") { subtotal ->
            formatMoney(subtotal.amountMinor, subtotal.currency)
        }
    }

    private fun humanizeCartError(error: Throwable): String {
        val apiError = (error as? ApiClientException)?.error
        return when (apiError) {
            "variant_not_available", "out_of_stock", "hold_conflict" ->
                "Текущего остатка недостаточно. Уменьшите количество или удалите позицию."
            else -> error.message ?: error.toString()
        }
    }

    private fun humanizeCheckoutSubmitError(error: Throwable): String {
        val apiError = (error as? ApiClientException)?.error
        return when (apiError) {
            "hold_conflict" -> "Не удалось зарезервировать остаток. Проверьте корзину и попробуйте снова."
            "out_of_stock", "variant_not_available" ->
                "Часть товаров недоступна. Уменьшите количество или удалите позицию в корзине."
            "idempotency_in_progress" -> "Оформление уже выполняется. Повторите через пару секунд."
            else -> "Ошибка оформления: ${error.message ?: error.toString()}"
        }
    }

    private fun resolveScreen(startParam: String?, screenQuery: String?): String? {
        val normalizedScreen = screenQuery?.trim()?.lowercase()
        if (normalizedScreen == SCREEN_CART || normalizedScreen == SCREEN_CHECKOUT) {
            return normalizedScreen
        }
        val normalizedStartParam = startParam?.trim()?.lowercase()
        if (normalizedStartParam == SCREEN_CART || normalizedStartParam == SCREEN_CHECKOUT) {
            return normalizedStartParam
        }
        return null
    }

    private fun navigateToScreen(screen: String?) {
        val qp = UrlQuery.parse(window.location.search).toMutableMap()
        if (screen.isNullOrBlank()) {
            qp.remove("screen")
            val startParam = qp["tgWebAppStartParam"]
            if (startParam?.equals(SCREEN_CART, ignoreCase = true) == true ||
                startParam?.equals(SCREEN_CHECKOUT, ignoreCase = true) == true
            ) {
                qp.remove("tgWebAppStartParam")
            }
        } else {
            qp["screen"] = screen
        }
        val next = if (qp.isEmpty()) {
            window.location.pathname
        } else {
            val query = qp.entries.joinToString("&") { (key, value) -> "$key=${encodeURIComponent(value)}" }
            "${window.location.pathname}?$query"
        }
        window.location.assign(next)
    }

    private fun encodeURIComponent(s: String): String = js("encodeURIComponent")(s) as String

    private fun onBuyClicked(
        qtyRaw: String?,
        variantId: String?,
        name: String?,
        phone: String?,
        addr: String?,
        ok: Div,
        err: Div
    ) {
        ok.content = ""
        err.content = ""
        val item = currentItem ?: run {
            err.content = "Товар не загружен."
            return
        }
        val qty = max(DEFAULT_QUANTITY, qtyRaw?.toIntOrNull() ?: DEFAULT_QUANTITY)
        val currency = item.invoiceCurrency.uppercase()
        val baseMinor = item.prices?.baseAmountMinor ?: 0L
        val sumMinor = baseMinor * qty
        val addressJson = if (addr.isNullOrBlank()) {
            null
        } else {
            """{"name":${q(name)},"phone":${q(phone)},"addr":${q(addr)}}"""
        }

        scope.launch {
            runCatching {
                api.postOrder(
                    OrderCreateRequest(
                        itemId = item.id,
                        variantId = variantId,
                        qty = qty,
                        currency = currency,
                        amountMinor = sumMinor,
                        deliveryOption = if (!addr.isNullOrBlank()) "address" else null,
                        addressJson = addressJson
                    )
                )
            }.onSuccess { resp ->
                ok.content = "Заказ создан: ${resp.orderId} (статус ${resp.status}). Ожидайте счёт в чате."
            }.onFailure { e ->
                err.content = "Ошибка заказа: ${e.message ?: e.toString()}"
            }
        }
    }

    private fun onOfferClicked(qtyRaw: String?, variantId: String?, ok: Div, err: Div) {
        ok.content = ""
        err.content = ""
        val item = currentItem ?: run {
            err.content = "Товар не загружен."
            return
        }
        val baseMinor = item.prices?.baseAmountMinor ?: 0L
        val currency = item.prices?.baseCurrency ?: DEFAULT_CURRENCY
        val prompt = "Введите вашу цену (например, ${formatMoney(baseMinor, currency)})"
        val input = js("prompt")(prompt) as String?
        if (input.isNullOrBlank()) return
        val amountMinor = parseMoneyToMinor(input) ?: run {
            err.content = "Неверный формат суммы."
            return
        }
        val qty = max(DEFAULT_QUANTITY, qtyRaw?.toIntOrNull() ?: DEFAULT_QUANTITY)

        scope.launch {
            runCatching {
                api.postOffer(
                    OfferRequest(
                        itemId = item.id,
                        variantId = variantId,
                        qty = qty,
                        offerAmountMinor = amountMinor
                    )
                )
            }.onSuccess { resp ->
                when (resp.decision) {
                    "autoAccept" -> ok.content = "Предложение принято автоматически. Продолжайте оформление."
                    "counter" -> ok.content = "Контр-офер: ${formatMoney(resp.counterAmountMinor ?: 0, currency)}."
                    "reject" -> err.content = "Предложение отклонено."
                    else -> err.content = "Ответ: ${resp.decision}"
                }
            }.onFailure { e ->
                err.content = "Ошибка офера: ${e.message ?: e.toString()}"
            }
        }
    }

    private fun onSubscribePriceDrop(targetRaw: String?, ok: Div, err: Div) {
        ok.content = ""
        err.content = ""
        val item = currentItem ?: run {
            err.content = "Товар не загружен."
            return
        }
        val normalized = targetRaw?.trim().orEmpty()
        val targetMinor = if (normalized.isEmpty()) {
            null
        } else {
            parseMoneyToMinor(normalized) ?: run {
                err.content = "Неверная целевая цена."
                return
            }
        }
        scope.launch {
            runCatching {
                api.subscribeWatchlist(
                    WatchlistSubscribeRequest(
                        itemId = item.id,
                        trigger = "price_drop",
                        variantId = null,
                        targetMinor = targetMinor
                    )
                )
            }.onSuccess {
                ok.content = "Мы сообщим, когда цена снизится."
            }.onFailure { e ->
                err.content = "Не удалось сохранить подписку: ${e.message ?: e.toString()}"
            }
        }
    }

    private fun onSubscribeRestock(variantId: String?, ok: Div, err: Div) {
        ok.content = ""
        err.content = ""
        val item = currentItem ?: run {
            err.content = "Товар не загружен."
            return
        }
        val normalizedVariant = variantId?.takeIf { it.isNotBlank() }
        scope.launch {
            runCatching {
                api.subscribeWatchlist(
                    WatchlistSubscribeRequest(
                        itemId = item.id,
                        trigger = "restock",
                        variantId = normalizedVariant
                    )
                )
            }.onSuccess {
                ok.content = "Сообщим, когда вернётся в наличии."
            }.onFailure { e ->
                err.content = "Не удалось сохранить подписку: ${e.message ?: e.toString()}"
            }
        }
    }

    private fun buildPriceLine(item: ItemResponse): String {
        val invoiceCurrency = item.invoiceCurrency.uppercase()
        val prices = item.prices ?: return "Цена: <i>уточняется</i> (инвойс: $invoiceCurrency)"
        val baseCode = prices.baseCurrency.uppercase()
        val parts = mutableListOf("<b>${formatMoney(prices.baseAmountMinor, baseCode)}</b>")
        fun append(code: String, amount: Long?) {
            if (amount != null && code.uppercase() != baseCode) {
                parts += formatMoney(amount, code)
            }
        }
        val invoiceAmount = prices.invoiceMinor
        if (invoiceAmount != null && invoiceCurrency != baseCode) {
            parts += formatMoney(invoiceAmount, invoiceCurrency)
        }
        append("USD", prices.usd)
        append("EUR", prices.eur)
        append("RUB", prices.rub)
        append("USDT_TS", prices.usdtTs)
        return "Цена: ${parts.joinToString(" / ")} (инвойс: $invoiceCurrency)"
    }

    private fun formatMoney(amountMinor: Long, currency: String): String {
        val absolute = abs(amountMinor)
        val major = absolute / MINOR_UNITS_IN_MAJOR
        val minor = (absolute % MINOR_UNITS_IN_MAJOR).toInt()
        val num = "$major.${minor.toString().padStart(MINOR_DIGITS, '0')}"
        val sign = if (amountMinor < 0) "-" else ""
        return "$sign$num ${currency.uppercase()}"
    }

    private fun parseMoneyToMinor(input: String): Long? {
        val norm = input.trim().replace(" ", "").replace(",", ".")
        val parts = norm.split(".")
        return when (parts.size) {
            1 -> parts[0].toLongOrNull()?.times(MINOR_UNITS_IN_MAJOR)
            2 -> {
                val major = parts[0].toLongOrNull() ?: return null
                val minor = parts[1]
                    .padEnd(MINOR_DIGITS, '0')
                    .take(MINOR_DIGITS)
                    .toIntOrNull() ?: return null
                major * MINOR_UNITS_IN_MAJOR + minor
            }

            else -> null
        }
    }

    private fun createIdempotencyKey(): String {
        return js("globalThis.crypto && globalThis.crypto.randomUUID ? globalThis.crypto.randomUUID() : null") as String?
            ?: (
                "id-" + (js("Date.now().toString(36) + '-' + Math.random().toString(16).slice(2)") as String)
            )
    }

    private fun q(value: String?): String = js("JSON.stringify")(value ?: "") as String
    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

fun main() {
    val hot = js("import.meta.webpackHot").unsafeCast<Hot?>()
    val qp = UrlQuery.parse(window.location.search)
    val isAdmin = qp["admin"] == "1" || qp["mode"] == "admin"
    startApplication(
        if (isAdmin) ::AdminApp else ::MiniApp,
        hot,
        BootstrapModule,
        BootstrapCssModule,
        TomSelectModule,
        CoreModule
    )
}

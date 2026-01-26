package com.example.miniapp

import com.example.miniapp.api.ApiClient
import com.example.miniapp.api.ItemResponse
import com.example.miniapp.api.LinkResolveRequest
import com.example.miniapp.api.OfferAcceptRequest
import com.example.miniapp.api.OfferRequest
import com.example.miniapp.api.OrderCreateRequest
import com.example.miniapp.api.WatchlistSubscribeRequest
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private const val DEFAULT_QUANTITY = 1
private const val MINOR_UNITS_IN_MAJOR = 100
private const val MINOR_DIGITS = 2
private const val DEFAULT_CURRENCY = "RUB"

class MiniApp : Application() {
    private val scope = MainScope()
    private val api = ApiClient()
    private var currentItem: ItemResponse? = null

    override fun start(state: Map<String, Any>) {
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

                val qp = UrlQuery.parse(window.location.search)
                val directItemId = qp["item"]
                val startToken = TelegramBridge.startParam()
                when {
                    directItemId != null -> loadItem(directItemId, titleEl, descEl, priceEl, infoEl, variantSelect)
                    startToken != null -> resolveStartToken(
                        startToken,
                        titleEl,
                        descEl,
                        priceEl,
                        infoEl,
                        variantSelect
                    )
                    else -> {
                        titleEl.content = "Не указан товар"
                        descEl.content = "Откройте Mini App по кнопке «Купить» или передайте ?item=<ID>."
                    }
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

    private fun resolveStartToken(
        token: String,
        titleEl: Div,
        descEl: Div,
        priceEl: Div,
        infoEl: Div,
        variantSelect: TomSelect
    ) {
        TelegramBridge.ready()
        scope.launch {
            runCatching { api.resolveLink(LinkResolveRequest(token = token)) }
                .onSuccess { resp ->
                    val itemId = resp.item?.itemId
                        ?: runCatching { StartAppCodecJs.decode(token).itemId }.getOrNull()
                    if (itemId == null) {
                        titleEl.content = "Ссылка недоступна"
                        descEl.content = "Ссылка устарела или недействительна."
                        return@onSuccess
                    }
                    loadItem(itemId, titleEl, descEl, priceEl, infoEl, variantSelect)
                }
                .onFailure {
                    val fallback = runCatching { StartAppCodecJs.decode(token).itemId }.getOrNull()
                    if (fallback != null) {
                        loadItem(fallback, titleEl, descEl, priceEl, infoEl, variantSelect)
                    } else {
                        titleEl.content = "Ссылка недоступна"
                        descEl.content = "Ссылка устарела или недействительна."
                    }
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

    private fun q(value: String?): String = js("JSON.stringify")(value ?: "") as String
    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

fun main() {
    val hot = js("import.meta.webpackHot").unsafeCast<Hot?>()
    startApplication(
        ::MiniApp,
        hot,
        BootstrapModule,
        BootstrapCssModule,
        TomSelectModule,
        CoreModule
    )
}

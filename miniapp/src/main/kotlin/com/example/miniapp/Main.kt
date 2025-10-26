package com.example.miniapp

import com.example.miniapp.api.ApiClient
import com.example.miniapp.api.ItemResponse
import com.example.miniapp.api.OfferRequest
import com.example.miniapp.api.OrderCreateRequest
import com.example.miniapp.startapp.StartAppCodecJs
import com.example.miniapp.tg.TelegramBridge
import com.example.miniapp.tg.UrlQuery
import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.form.select.SimpleSelect
import io.kvision.form.spinner.Spinner
import io.kvision.form.text.TextArea
import io.kvision.form.text.TextInput
import io.kvision.form.text.TextInputType
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.panel.Root
import io.kvision.panel.vPanel
import io.kvision.utils.onEvent
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private val scope = MainScope()

fun main() {
    Root("kvapp").vPanel(spacing = 8) {
        TelegramBridge.ready()
        AppUI(this).start()
    }
}

class AppUI(private val container: Container) {

    private val api = ApiClient(baseUrl = "")
    private var currentItem: ItemResponse? = null

    private val titleEl = Div().apply { addCssClass("h1") }
    private val descEl = Div()
    private val priceEl = Div()
    private val infoEl = Div().apply { addCssClass("muted") }

    private val variantSelect = SimpleSelect(
        options = listOf<Pair<String, String>>(),
        placeholder = "Выберите вариант (если есть)",
        emptyOption = true
    )
    private val qtyInput = Spinner(value = 1, min = 1, max = 99, decimals = 0)

    private val nameInput = TextInput(TextInputType.TEXT) { placeholder = "Ваше имя" }
    private val phoneInput = TextInput(TextInputType.TEL) { placeholder = "Телефон" }
    private val addrInput = TextArea(rows = 3) { placeholder = "Адрес доставки (если требуется)" }

    private val statusOk = Div().apply { addCssClass("ok") }
    private val statusErr = Div().apply { addCssClass("err") }

    fun start() {
        container.add(
            Div().apply {
                addCssClass("card")
                add(titleEl)
                add(descEl)
                add(priceEl)
                add(infoEl)
                add(Div().apply {
                    addCssClass("h2")
                    content = "Параметры"
                })
                add(Div().apply {
                    addCssClass("row")
                    add(variantSelect)
                    add(Div("Кол-во:"))
                    add(qtyInput)
                })
                add(Div().apply {
                    addCssClass("h2")
                    content = "Контакты / адрес"
                })
                add(Div().apply {
                    addCssClass("row")
                    add(nameInput)
                    add(phoneInput)
                })
                add(addrInput)
                add(Div().apply {
                    addCssClass("buttons")
                    add(Button("Купить").apply {
                        addCssClass("primary")
                        onClick { onBuyClicked() }
                    })
                    add(Button("Предложить цену").apply {
                        addCssClass("secondary")
                        onClick { onOfferClicked() }
                    })
                })
                add(statusOk)
                add(statusErr)
            }
        )

        val query = UrlQuery.parse(window.location.search)
        val itemId = query["item"] ?: TelegramBridge.startParam()?.let { StartAppCodecJs.decode(it).itemId }
        if (itemId == null) {
            titleEl.content = "Не указан товар"
            descEl.content = "Откройте Mini App по кнопке «Купить» под постом или передайте ?item=<ID>."
            return
        }
        loadItem(itemId)
    }

    private fun loadItem(itemId: String) {
        statusOk.content = ""
        statusErr.content = ""
        scope.launch {
            try {
                val item = api.getItem(itemId)
                currentItem = item
                renderItem(item)
            } catch (e: dynamic) {
                statusErr.content = "Ошибка загрузки товара: ${e?.message ?: e.toString()}"
            }
        }
    }

    private fun renderItem(item: ItemResponse) {
        titleEl.content = item.title
        descEl.content = escape(item.description)
        val price = item.prices
        priceEl.content = if (price != null) {
            "Цена: <b>${formatMoney(price.baseAmountMinor, price.baseCurrency)}</b>"
        } else {
            "Цена: <i>уточняется</i>"
        }
        infoEl.content = "ID: ${item.id}"

        val options = item.variants.filter { it.active }.map { it.id to (it.size ?: it.sku ?: it.id) }
        variantSelect.setOptions(options)

        nameInput.value = localGet("name") ?: ""
        phoneInput.value = localGet("phone") ?: ""
        addrInput.value = localGet("addr") ?: ""

        nameInput.onEvent {
            change { localSet("name", nameInput.value ?: "") }
        }
        phoneInput.onEvent {
            change { localSet("phone", phoneInput.value ?: "") }
        }
        addrInput.onEvent {
            change { localSet("addr", addrInput.value ?: "") }
        }
    }

    private fun onBuyClicked() {
        statusOk.content = ""
        statusErr.content = ""
        val item = currentItem ?: return
        val quantity = max(1, qtyInput.value ?: 1)
        val currency = item.prices?.baseCurrency ?: "RUB"
        val basePrice = item.prices?.baseAmountMinor ?: 0L
        val amountMinor = basePrice * quantity

        val addressJson = """{"name":${stringify(nameInput.value)}, "phone":${stringify(phoneInput.value)}, "addr":${stringify(addrInput.value)}}"""

        scope.launch {
            try {
                val response = api.postOrder(
                    OrderCreateRequest(
                        itemId = item.id,
                        variantId = variantSelect.value,
                        qty = quantity,
                        currency = currency,
                        amountMinor = amountMinor,
                        deliveryOption = if (addrInput.value.isNullOrBlank()) null else "address",
                        addressJson = if (addrInput.value.isNullOrBlank()) null else addressJson
                    )
                )
                statusOk.content = "Заказ создан: ${response.orderId} (статус ${response.status}). Ожидайте счёт в чате."
            } catch (e: dynamic) {
                statusErr.content = "Ошибка заказа: ${e?.message ?: e.toString()}"
            }
        }
    }

    private fun onOfferClicked() {
        statusOk.content = ""
        statusErr.content = ""
        val item = currentItem ?: return
        val basePrice = item.prices?.baseAmountMinor ?: 0L
        val promptValue = js("prompt")("Введите вашу цену (например, ${formatMoney(basePrice, item.prices?.baseCurrency ?: "RUB")})") as String?
        if (promptValue.isNullOrBlank()) return
        val amountMinor = parseMoneyToMinor(promptValue, item.prices?.baseCurrency ?: "RUB") ?: run {
            statusErr.content = "Неверный формат суммы."
            return
        }
        val quantity = max(1, qtyInput.value ?: 1)

        scope.launch {
            try {
                val response = api.postOffer(
                    OfferRequest(
                        itemId = item.id,
                        variantId = variantSelect.value,
                        qty = quantity,
                        offerAmountMinor = amountMinor
                    )
                )
                when (response.decision) {
                    "autoAccept" -> statusOk.content = "Предложение принято автоматически. Продолжайте оформление."
                    "counter" -> statusOk.content = "Продавец предлагает ${formatMoney(response.counterAmountMinor ?: 0L, item.prices?.baseCurrency ?: "RUB")}."
                    "reject" -> statusErr.content = "Предложение отклонено."
                    else -> statusErr.content = "Ответ: ${response.decision}"
                }
            } catch (e: dynamic) {
                statusErr.content = "Ошибка офера: ${e?.message ?: e.toString()}"
            }
        }
    }

    private fun formatMoney(amountMinor: Long, currency: String): String {
        val absValue = abs(amountMinor)
        val major = absValue / 100
        val minor = (absValue % 100).toInt()
        val formatted = "%d.%02d".format(major, minor)
        val sign = if (amountMinor < 0) "-" else ""
        return "$sign$formatted ${currency.uppercase()}"
    }

    private fun parseMoneyToMinor(value: String, currency: String): Long? {
        val cleaned = value.trim().replace(" ", "").replace(currency, "", ignoreCase = true)
        val normalized = cleaned.replace(",", ".")
        val parts = normalized.split(".")
        return when (parts.size) {
            1 -> parts[0].toLongOrNull()?.times(100)
            2 -> {
                val major = parts[0].toLongOrNull() ?: return null
                val minor = parts[1].padEnd(2, '0').take(2).toIntOrNull() ?: return null
                major * 100 + minor
            }
            else -> null
        }
    }

    private fun stringify(value: String?): String = js("JSON.stringify")(value ?: "") as String

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun localGet(key: String): String? = runCatching {
        window.localStorage.getItem("miniapp.$key")
    }.getOrNull()

    private fun localSet(key: String, value: String) {
        runCatching {
            window.localStorage.setItem("miniapp.$key", value)
        }
    }
}

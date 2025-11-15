package com.example.miniapp

import com.example.miniapp.api.ApiClient
import com.example.miniapp.api.ItemResponse
import com.example.miniapp.api.OfferRequest
import com.example.miniapp.api.OrderCreateRequest
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
                    value = "1"
                }
                val nameInput = TextInput(InputType.TEXT).apply { placeholder = "Ваше имя" }
                val phoneInput = TextInput(InputType.TEL).apply { placeholder = "Телефон" }
                val addrInput = TextArea(rows = 3).apply { placeholder = "Адрес доставки (если требуется)" }

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
                        add(Button("Купить", className = "primary").apply {
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
                        })
                        add(
                            Button("Предложить цену", className = "secondary").apply {
                                onClick { onOfferClicked(qtyInput.value, variantSelect.value, statusOk, statusErr) }
                            }
                        )
                    })
                    add(statusOk)
                    add(statusErr)
                })

                val qp = UrlQuery.parse(window.location.search)
                val itemId = qp["item"] ?: TelegramBridge.startParam()?.let { StartAppCodecJs.decode(it).itemId }
                if (itemId == null) {
                    titleEl.content = "Не указан товар"
                    descEl.content = "Откройте Mini App по кнопке «Купить» или передайте ?item=<ID>."
                } else {
                    loadItem(itemId, titleEl, descEl, priceEl, infoEl, variantSelect)
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
        val p = item.prices
        priceEl.content = if (p != null) {
            "Цена: <b>${formatMoney(p.baseAmountMinor, p.baseCurrency)}</b>"
        } else {
            "Цена: <i>уточняется</i>"
        }
        infoEl.content = "ID: ${item.id}"
        val options = item.variants.filter { it.active }.map { it.id to (it.size ?: it.sku ?: it.id) }
        variantSelect.options = options
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
        val qty = max(1, qtyRaw?.toIntOrNull() ?: 1)
        val currency = item.prices?.baseCurrency ?: "RUB"
        val baseMinor = item.prices?.baseAmountMinor ?: 0L
        val sumMinor = baseMinor * qty
        val addressJson = if (addr.isNullOrBlank()) null
        else """{"name":${q(name)},"phone":${q(phone)},"addr":${q(addr)}}"""

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
        val currency = item.prices?.baseCurrency ?: "RUB"
        val prompt = "Введите вашу цену (например, ${formatMoney(baseMinor, currency)})"
        val input = js("prompt")(prompt) as String?
        if (input.isNullOrBlank()) return
        val amountMinor = parseMoneyToMinor(input) ?: run {
            err.content = "Неверный формат суммы."
            return
        }
        val qty = max(1, qtyRaw?.toIntOrNull() ?: 1)

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

    private fun formatMoney(amountMinor: Long, currency: String): String {
        val absolute = abs(amountMinor)
        val major = absolute / 100
        val minor = (absolute % 100).toInt()
        val num = "$major.${minor.toString().padStart(2, '0')}"
        val sign = if (amountMinor < 0) "-" else ""
        return "$sign$num ${currency.uppercase()}"
    }

    private fun parseMoneyToMinor(input: String): Long? {
        val norm = input.trim().replace(" ", "").replace(",", ".")
        val parts = norm.split(".")
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

    private fun q(value: String?): String = js("JSON.stringify")(value ?: "") as String
    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

fun main() {
    val hot = js("import.meta.webpackHot").unsafeCast<Hot?>()
    startApplication(::MiniApp, hot, BootstrapModule, BootstrapCssModule, TomSelectModule, CoreModule)
}

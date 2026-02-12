package com.example.miniapp.tg

import kotlinx.browser.window

object TelegramBridge {
    private val wa: dynamic = js(
        "typeof window !== 'undefined' ? (window.Telegram ? window.Telegram.WebApp : null) : null"
    )

    fun userIdOrNull(): Long? = try {
        val u = wa?.initDataUnsafe?.user
        val id = u?.id as? Int ?: (u?.id as? Double)?.toInt()
        id?.toLong()
    } catch (_: dynamic) { null }

    fun startParam(): String? = try {
        val url = window.location.search
        val qp = UrlQuery.parse(url)
        qp["tgWebAppStartParam"]
    } catch (_: dynamic) { null }

    fun initDataRaw(): String? = try { wa?.initData as? String } catch (_: dynamic) { null }

    fun ready() {
        try {
            wa?.ready?.invoke()
        } catch (_: dynamic) {
        }
    }

    fun hapticSuccess() {
        try {
            wa?.HapticFeedback?.notificationOccurred?.invoke("success")
        } catch (_: dynamic) {
        }
    }

    fun closeIfAvailable(): Boolean {
        return try {
            if (wa != null && wa?.asDynamic()?.close != null) {
                wa.asDynamic().close()
                true
            } else {
                false
            }
        } catch (_: dynamic) {
            false
        }
    }
}

object UrlQuery {
    fun parse(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val s = if (query.startsWith("?")) query.substring(1) else query
        if (s.isBlank()) return emptyMap()
        return s.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) {
                null
            } else {
                val key = it.substring(0, i)
                val value = decodeURIComponent(it.substring(i + 1))
                key to value
            }
        }.toMap()
    }
    private fun decodeURIComponent(s: String): String = js("decodeURIComponent")(s) as String
}

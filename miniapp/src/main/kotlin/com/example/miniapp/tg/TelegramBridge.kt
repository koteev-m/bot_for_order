package com.example.miniapp.tg

import kotlinx.browser.window

object TelegramBridge {
    private val webApp: dynamic = js(
        "typeof window !== 'undefined' ? (window.Telegram ? window.Telegram.WebApp : null) : null"
    )

    fun userIdOrNull(): Long? = try {
        val user = webApp?.initDataUnsafe?.user
        val id = (user?.id as? Double)?.toLong() ?: (user?.id as? Int)?.toLong()
        id
    } catch (_: dynamic) {
        null
    }

    fun startParam(): String? = try {
        val query = window.location.search
        val params = UrlQuery.parse(query)
        params["tgWebAppStartParam"]
    } catch (_: dynamic) {
        null
    }

    fun isAvailable(): Boolean = webApp != null

    fun ready() {
        try {
            webApp?.ready?.invoke()
        } catch (_: dynamic) {
            // ignore
        }
    }
}

object UrlQuery {
    fun parse(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val trimmed = if (query.startsWith("?")) query.drop(1) else query
        if (trimmed.isBlank()) return emptyMap()
        return trimmed.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) {
                null
            } else {
                val key = part.substring(0, idx)
                val value = decodeURIComponent(part.substring(idx + 1))
                key to value
            }
        }.toMap()
    }

    private fun decodeURIComponent(value: String): String = js("decodeURIComponent")(value) as String
}

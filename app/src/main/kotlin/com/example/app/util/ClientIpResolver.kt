package com.example.app.util

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.util.Locale

object ClientIpResolver {
    private val fallbackHeaders = listOf(
        "True-Client-IP",
        "CF-Connecting-IP",
        "X-Real-IP"
    )

    /**
     * Возвращает эффективный IP клиента.
     * Логика:
     *  - Если [trustedProxies] пуст или remoteHost не в trusted-прокси -> вернуть remoteHost.
     *  - Иначе: если есть X-Forwarded-For (приоритет) или Forwarded (только если XFF пустой),
     *    идём справа-налево и пропускаем trusted-прокси.
     *  - Если цепочка пуста, fallback: True-Client-IP → CF-Connecting-IP → X-Real-IP (по порядку).
     */
    fun resolve(call: ApplicationCall, trustedProxies: Set<String>): String {
        // Используем штатный Ktor API: request.local.remoteHost — реальный адрес пира
        val remote = call.request.local.remoteHost
        if (trustedProxies.isEmpty() || !CidrMatcher.isAllowed(remote, trustedProxies)) {
            return remote
        }

        val xff = parseXff(call.request.headers[HttpHeaders.XForwardedFor])
        val forwarded = if (xff.isEmpty()) parseForwarded(call.request.headers[HttpHeaders.Forwarded]) else emptyList()
        val chain = if (xff.isNotEmpty()) xff else forwarded

        val client = if (chain.isNotEmpty()) {
            // Для XFF/Forwarded идём справа-налево: пропускаем trusted-прокси, первый не-trusted — это клиент
            chain.asReversed().firstOrNull { ip -> !CidrMatcher.isAllowed(ip, trustedProxies) }
        } else {
            // Приоритет как задокументировано: True-Client-IP → CF-Connecting-IP → X-Real-IP
            val fallbackChain = fallbackHeaders.mapNotNull { header ->
                cleanIpToken(call.request.headers[header])
            }
            fallbackChain.firstOrNull { ip -> !CidrMatcher.isAllowed(ip, trustedProxies) }
        }
        return client ?: remote
    }

    // --- Helpers ---
    private fun parseXff(header: String?): List<String> =
        header
            ?.split(',')
            ?.mapNotNull { cleanIpToken(it) }
            ?: emptyList()

    // RFC 7239: Forwarded: for=203.0.113.5;proto=https, for=127.0.0.1
    private fun parseForwarded(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return header.split(',')
            .map { it.trim() }
            .mapNotNull { elem ->
                val forPart = elem.split(';')
                    .firstOrNull { it.trim().startsWith("for=", ignoreCase = true) }
                    ?: return@mapNotNull null
                val raw = forPart.substringAfter('=', "").trim().trim('"')
                cleanIpToken(raw)
            }
    }

    /**
     * Приводит токен адреса к IP без порта, игнорирует "unknown"/обфусцированные.
     * Поддерживает:
     *  - IPv4: "203.0.113.5:4321" -> "203.0.113.5"
     *  - IPv6 в скобках: "[2001:db8::1]:1234" -> "2001:db8::1"
     *  - Чистый IPv6 без порта: "2001:db8::1" -> "2001:db8::1"
     */
    private fun cleanIpToken(token: String?): String? {
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("unknown", ignoreCase = true) || trimmed.startsWith("_")) return null

        // Снимаем скобки [..] и zone-id (%eth0)
        val withoutBrackets = if (trimmed.startsWith("[")) {
            trimmed.substringAfter('[').substringBefore(']')
        } else {
            trimmed
        }
        val noZone = withoutBrackets.substringBefore('%')

        var value = noZone

        // 1) Сперва нормализуем IPv4-mapped IPv6: ::ffff:203.0.113.5 -> 203.0.113.5
        val lower = value.lowercase(Locale.ROOT)
        if (lower.startsWith("::ffff:")) {
            val candidate = value.substring(7) // после "::ffff:"
            if (candidate.contains('.')) {
                value = candidate
            }
        }

        // 2) Только для чистой IPv4-формы отрезаем :port (если он есть)
        // Эвристика: двоеточие стоит ПОСЛЕ последней точки — значит это порт IPv4.
        if (value.contains('.')) {
            val lastDot = value.lastIndexOf('.')
            val lastColon = value.lastIndexOf(':')
            if (lastColon > lastDot) {
                value = value.substring(0, lastColon)
            }
        }

        if (value.isBlank() || !looksLikeIpToken(value)) return null
        return value
    }

    private fun looksLikeIpToken(value: String): Boolean {
        return if (value.contains(':')) {
            value.any { ch -> ch.isDigit() || ch in "abcdefABCDEF" } &&
                value.all { ch -> ch.isDigit() || ch in "abcdefABCDEF:." }
        } else {
            value.contains('.') &&
                value.any { ch -> ch.isDigit() } &&
                value.all { ch -> ch.isDigit() || ch == '.' }
        }
    }
}

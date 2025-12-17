package com.example.app.util

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

object ClientIpResolver {
    /**
     * Returns the effective client IP.
     * Logic:
     *  - If [trustedProxies] is empty -> return remoteHost (ignore XFF).
     *  - If remoteHost ∉ trustedProxies -> return remoteHost (ignore XFF).
     *  - Else walk X-Forwarded-For right-to-left; skip entries that ∈ trustedProxies; first non-proxy is client IP.
     *  - On empty/malformed XFF fallback to remoteHost.
     */
    fun resolve(call: ApplicationCall, trustedProxies: Set<String>): String {
        // Используем штатный Ktor API: request.local.remoteHost — реальный адрес пира
        val remote = call.request.local.remoteHost
        if (trustedProxies.isEmpty() || !CidrMatcher.isAllowed(remote, trustedProxies)) {
            return remote
        }

        val xff = parseXff(call.request.headers[HttpHeaders.XForwardedFor])
        val fwd = if (xff.isEmpty()) parseForwarded(call.request.headers[HttpHeaders.Forwarded]) else emptyList()
        var chain = if (xff.isNotEmpty()) xff else fwd

        if (chain.isEmpty()) {
            val realIp = cleanIpToken(call.request.headers["X-Real-IP"])
            val cfIp = cleanIpToken(call.request.headers["CF-Connecting-IP"])
            val tciIp = cleanIpToken(call.request.headers["True-Client-IP"])
            chain = listOfNotNull(realIp, cfIp, tciIp)
        }
        // Идём справа-налево: пропускаем trusted прокси, первый не-trusted — это клиент
        val client = chain.asReversed().firstOrNull { ip -> !CidrMatcher.isAllowed(ip, trustedProxies) }
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
        val withoutBrackets = if (trimmed.startsWith("[")) {
            trimmed.substringAfter('[').substringBefore(']')
        } else {
            trimmed
        }
        // Отрезаем zone-id (fe80::1%eth0) если встретится
        val noZone = withoutBrackets.substringBefore('%')
        // IPv4 с :port -> отбрасываем порт
        val normalized = if (noZone.indexOf('.') >= 0) {
            noZone.substringBeforeLast(':')
        } else {
            noZone
        }

        var value = normalized
        if (value.lowercase().startsWith("::ffff:") && value.removePrefix("::ffff:").contains('.')) {
            value = value.removePrefix("::ffff:")
        }

        return when {
            trimmed.isEmpty() -> null
            trimmed.equals("unknown", ignoreCase = true) || trimmed.startsWith("_") -> null
            value.isBlank() -> null
            else -> value
        }
    }
}

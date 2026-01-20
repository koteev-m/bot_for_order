package com.example.bots.startapp

/**
 * Генератор прямой ссылки для Mini App:
 * https://t.me/<botUsername>/<appName>?startapp=<ENC>
 *
 * Если appName не используется, обычно применяют форму:
 * https://t.me/<botUsername>?startapp=<ENC>
 */
object DirectLink {
    fun forMiniApp(botUsername: String, appName: String?, param: StartAppParam): String {
        val enc = StartAppCodec.encode(param)
        val base = buildString {
            append("https://t.me/").append(botUsername)
            appName?.takeIf { it.isNotBlank() }?.let { append("/").append(it) }
        }
        return "$base?startapp=$enc"
    }

    fun forMiniApp(botUsername: String, appName: String?, token: String, mode: MiniAppMode): String {
        val base = buildString {
            append("https://t.me/").append(botUsername)
            appName?.takeIf { it.isNotBlank() }?.let { append("/").append(it) }
        }
        return "$base?startapp=$token&mode=${mode.value}"
    }
}

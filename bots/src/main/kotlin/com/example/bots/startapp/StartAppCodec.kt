package com.example.bots.startapp

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Кодек формата:
 *  raw: "item:<itemId>|variant:<variantId>|ref:<ref>"
 *  → base64url без '='
 *
 * Консервативный лимит длины — 64 символа для base64url (deep-link лимиты).
 */
object StartAppCodec {

    private const val MAX_B64URL_LENGTH = 64

    fun encode(param: StartAppParam): String {
        val pieces = buildList {
            add("item:${param.itemId}")
            param.variantId?.let { add("variant:$it") }
            param.ref?.let { add("ref:${sanitizeRef(it)}") }
        }
        val raw = pieces.joinToString(separator = "|")
        val b64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        require(b64.length <= MAX_B64URL_LENGTH) {
            "startapp parameter too long (${b64.length} > $MAX_B64URL_LENGTH)"
        }
        return b64
    }

    fun decode(b64: String): StartAppParam {
        require(b64.isNotBlank() && b64.length <= MAX_B64URL_LENGTH) {
            "invalid startapp parameter length"
        }
        val raw = runCatching {
            String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("invalid base64url in startapp") }

        var itemId: String? = null
        var variantId: String? = null
        var ref: String? = null

        raw.split("|").forEach { part ->
            val idx = part.indexOf(':')
            require(idx > 0 && idx < part.length - 1) { "invalid part: $part" }
            val key = part.substring(0, idx)
            val value = part.substring(idx + 1)
            when (key) {
                "item" -> itemId = value
                "variant" -> variantId = value
                "ref" -> ref = value
                else -> error("unknown key: $key")
            }
        }
        val iid = itemId ?: error("item is required in startapp")
        return StartAppParam(itemId = iid, variantId = variantId, ref = ref)
    }

    private fun sanitizeRef(ref: String): String =
        ref.replace("|", "_").replace(":", "_").take(24)
}

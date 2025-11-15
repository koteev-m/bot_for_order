package com.example.miniapp.startapp

data class StartAppParam(val itemId: String, val variantId: String? = null, val ref: String? = null)

object StartAppCodecJs {
    private const val MAX_B64URL_LENGTH = 64

    fun decode(b64: String): StartAppParam {
        require(b64.isNotBlank() && b64.length <= MAX_B64URL_LENGTH) { "invalid startapp parameter length" }
        val raw = kotlin.runCatching { urlBase64Decode(b64) }
            .getOrElse { throw IllegalArgumentException("invalid base64url in startapp") }
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
        return StartAppParam(itemId ?: error("item is required"), variantId, ref)
    }

    private fun urlBase64Decode(b64: String): String {
        val std = b64.replace('-', '+').replace('_', '/')
        val pad = (4 - std.length % 4) % 4
        val padded = std + "=".repeat(pad)
        return js("atob")(padded) as String
    }
}

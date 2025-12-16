package com.example.app.util

import java.net.InetAddress

/**
 * Utility helpers to match IP addresses against CIDR/host allowlists.
 */
object CidrMatcher {
    /**
     * Returns true when [clientIp] is contained in [allowlist].
     * Entries can be exact addresses or CIDR notations; malformed entries are ignored.
     */
    fun isAllowed(clientIp: String?, allowlist: Set<String>): Boolean {
        if (allowlist.isEmpty()) return true
        val address = clientIp?.toInetAddressOrNull()
        return address != null && allowlist.any { entry ->
            val trimmed = entry.trim()
            when {
                trimmed.contains('/') -> trimmed.toCidrBlockOrNull()?.matches(address) ?: false
                else -> trimmed.toInetAddressOrNull()?.let { it == address } ?: false
            }
        }
    }

    private fun String.toInetAddressOrNull(): InetAddress? =
        runCatching { InetAddress.getByName(this) }.getOrNull()

    private fun String.toCidrBlockOrNull(): CidrBlock? {
        val parts = split("/", limit = 2)
        if (parts.size != 2) return null
        val network = parts[0].trim().toInetAddressOrNull()
        val prefix = parts[1].toIntOrNull()
        val maxPrefix = network?.address?.size?.times(8)
        return when {
            network == null || prefix == null || maxPrefix == null -> null
            prefix !in 0..maxPrefix -> null
            else -> CidrBlock(network, prefix)
        }
    }

    private data class CidrBlock(
        val network: InetAddress,
        val prefix: Int,
    ) {
        fun matches(address: InetAddress): Boolean {
            val candidate = address.address
            val networkBytes = network.address
            if (candidate.size != networkBytes.size) return false
            var remaining = prefix
            var idx = 0
            var matches = true
            while (remaining >= 8) {
                matches = matches && candidate[idx] == networkBytes[idx]
                remaining -= 8
                idx++
            }
            val matchesPrefix = if (remaining == 0) {
                true
            } else {
                val mask = (0xFF shl (8 - remaining)).toByte()
                (candidate.getOrElse(idx) { 0 }.toInt() and mask.toInt()) ==
                    (networkBytes.getOrElse(idx) { 0 }.toInt() and mask.toInt())
            }
            return matches && matchesPrefix
        }
    }
}

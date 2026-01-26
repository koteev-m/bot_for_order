package com.example.app.services

import com.example.bots.startapp.StartAppCodec
import com.example.db.DuplicateTokenException
import com.example.db.LinkContextRepository
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

interface LinkTokenGenerator {
    fun generate(): String
}

class SecureRandomLinkTokenGenerator(
    private val secureRandom: SecureRandom = SecureRandom()
) : LinkTokenGenerator {
    override fun generate(): String {
        val bytes = ByteArray(18)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

data class LinkContextInput(
    val merchantId: String? = null,
    val storefrontId: String? = null,
    val channelId: Long? = null,
    val postId: Long? = null,
    val button: LinkButton? = null,
    val action: LinkAction,
    val itemId: String? = null,
    val variantHint: String? = null,
    val expiresAt: Instant? = null,
    val metaJson: String? = null
)

sealed class LinkResolveResult {
    data class Found(val context: LinkContext, val legacy: Boolean) : LinkResolveResult()
    data object NotFound : LinkResolveResult()
    data object Expired : LinkResolveResult()
    data object Revoked : LinkResolveResult()
}

class LinkContextService(
    private val repository: LinkContextRepository,
    private val tokenGenerator: LinkTokenGenerator,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun create(input: LinkContextInput): LinkContext {
        val now = Instant.now(clock)
        val base = LinkContext(
            id = 0,
            token = "",
            merchantId = input.merchantId,
            storefrontId = input.storefrontId,
            channelId = input.channelId,
            postId = input.postId,
            button = input.button,
            action = input.action,
            itemId = input.itemId,
            variantHint = input.variantHint,
            createdAt = now,
            expiresAt = input.expiresAt,
            revokedAt = null,
            metaJson = input.metaJson
        )
        val context = insertWithRetry(base)
        return context
    }

    suspend fun resolve(token: String): LinkResolveResult {
        val stored = repository.getByToken(token)
        if (stored != null) {
            if (stored.revokedAt != null) return LinkResolveResult.Revoked
            val now = Instant.now(clock)
            val expiresAt = stored.expiresAt
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                return LinkResolveResult.Expired
            }
            return LinkResolveResult.Found(stored, legacy = false)
        }

        val legacy = runCatching { StartAppCodec.decode(token) }.getOrNull() ?: return LinkResolveResult.NotFound
        val now = Instant.now(clock)
        val synthetic = LinkContext(
            id = 0,
            token = token,
            merchantId = null,
            storefrontId = null,
            channelId = null,
            postId = null,
            button = LinkButton.PRODUCT,
            action = LinkAction.open_product,
            itemId = legacy.itemId,
            variantHint = legacy.variantId,
            createdAt = now,
            expiresAt = null,
            revokedAt = null,
            metaJson = null
        )
        return LinkResolveResult.Found(synthetic, legacy = true)
    }

    private suspend fun insertWithRetry(base: LinkContext): LinkContext {
        repeat(5) {
            val token = tokenGenerator.generate()
            val candidate = base.copy(token = token)
            val inserted = runCatching { repository.create(candidate) }
            val id = inserted.getOrElse { error ->
                if (error is DuplicateTokenException) {
                    return@repeat
                }
                throw error
            }
            return candidate.copy(id = id)
        }
        error("Failed to generate unique link token after retries")
    }
}

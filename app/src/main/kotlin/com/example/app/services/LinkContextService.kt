package com.example.app.services

import com.example.db.LinkContextsRepository
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import java.time.Instant

data class LinkContextCreateRequest(
    val merchantId: String,
    val storefrontId: String,
    val channelId: Long,
    val postMessageId: Int?,
    val listingId: String,
    val action: LinkAction,
    val button: LinkButton,
    val expiresAt: Instant?,
    val metadataJson: String
)

data class LinkContextCreateResult(
    val token: String,
    val context: LinkContext
)

class LinkContextService(
    private val repository: LinkContextsRepository,
    private val tokenHasher: LinkTokenHasher
) {
    suspend fun create(request: LinkContextCreateRequest): LinkContextCreateResult {
        val token = tokenHasher.generateToken()
        val tokenHash = tokenHasher.hash(token)
        val now = Instant.now()
        val context = LinkContext(
            id = 0,
            tokenHash = tokenHash,
            merchantId = request.merchantId,
            storefrontId = request.storefrontId,
            channelId = request.channelId,
            postMessageId = request.postMessageId,
            listingId = request.listingId,
            action = request.action,
            button = request.button,
            createdAt = now,
            revokedAt = null,
            expiresAt = request.expiresAt,
            metadataJson = request.metadataJson
        )
        val id = repository.create(context)
        return LinkContextCreateResult(token = token, context = context.copy(id = id))
    }

    suspend fun getByToken(token: String): LinkContext? {
        for (hash in tokenHasher.hashesForLookup(token)) {
            val context = repository.getByTokenHash(hash)
            if (context != null) {
                return context
            }
        }
        return null
    }

    suspend fun revoke(token: String, revokedAt: Instant = Instant.now()): Boolean {
        for (hash in tokenHasher.hashesForLookup(token)) {
            if (repository.revokeByTokenHash(hash, revokedAt)) {
                return true
            }
        }
        return false
    }
}

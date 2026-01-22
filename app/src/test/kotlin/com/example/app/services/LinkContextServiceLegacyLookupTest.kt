package com.example.app.services

import com.example.db.LinkContextsRepository
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

class LinkContextServiceLegacyLookupTest : StringSpec({
    "getByToken and revoke handle legacy hashes" {
        val hasher = LinkTokenHasher("test-secret")
        val repository = LegacyInMemoryLinkContextsRepository()
        val legacyHash = hasher.hashLegacy("test-token")
        val context = LinkContext(
            id = 0,
            tokenHash = legacyHash,
            merchantId = "merchant-1",
            storefrontId = "storefront-1",
            channelId = 123L,
            postMessageId = 456,
            listingId = "listing-1",
            action = LinkAction.ADD,
            button = LinkButton.ADD,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            revokedAt = null,
            expiresAt = null,
            metadataJson = "{}"
        )
        repository.create(context)

        val service = LinkContextService(repository, hasher)

        service.getByToken("test-token").shouldNotBeNull().tokenHash shouldBe legacyHash
        service.revoke("test-token") shouldBe true
        repository.getByTokenHash(legacyHash).shouldNotBeNull().revokedAt.shouldNotBeNull()
    }
})

private class LegacyInMemoryLinkContextsRepository : LinkContextsRepository {
    private val storage = mutableMapOf<String, LinkContext>()
    private var nextId = 1L

    override suspend fun create(context: LinkContext): Long {
        val id = if (context.id == 0L) nextId++ else context.id
        storage[context.tokenHash] = context.copy(id = id)
        return id
    }

    override suspend fun getByTokenHash(tokenHash: String): LinkContext? {
        return storage[tokenHash]
    }

    override suspend fun revokeByTokenHash(tokenHash: String, revokedAt: Instant): Boolean {
        val existing = storage[tokenHash] ?: return false
        storage[tokenHash] = existing.copy(revokedAt = revokedAt)
        return true
    }
}

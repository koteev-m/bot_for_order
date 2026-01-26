package com.example.app

import com.example.app.services.LinkContextInput
import com.example.app.services.LinkContextService
import com.example.app.services.LinkTokenGenerator
import com.example.db.DuplicateTokenException
import com.example.db.LinkContextRepository
import com.example.domain.LinkAction
import com.example.domain.LinkContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LinkContextServiceTest : StringSpec({
    "retries on duplicate token collision" {
        val repository = CollisionLinkContextRepository()
        val generator = SequenceTokenGenerator(listOf("dup-token", "ok-token"))
        val service = LinkContextService(repository, generator)

        val created = service.create(
            LinkContextInput(
                action = LinkAction.open_product,
                itemId = "item-1"
            )
        )

        created.token shouldBe "ok-token"
        repository.createdTokens shouldBe listOf("dup-token", "ok-token")
    }
})

private class SequenceTokenGenerator(
    private val tokens: List<String>
) : LinkTokenGenerator {
    private var index = 0
    override fun generate(): String = tokens[index++]
}

private class CollisionLinkContextRepository : LinkContextRepository {
    val createdTokens = mutableListOf<String>()

    override suspend fun create(context: LinkContext): Long {
        createdTokens += context.token
        if (context.token == "dup-token") {
            throw DuplicateTokenException("duplicate")
        }
        return 1L
    }

    override suspend fun getByToken(token: String): LinkContext? = null
}

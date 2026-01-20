package com.example.app.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

class LinkTokenHasherTest : StringSpec({
    "hash returns canonical lower hex" {
        val hasher = LinkTokenHasher(TEST_SECRET)
        val hash = hasher.hash(TEST_TOKEN)

        hash.length shouldBe HASH_LENGTH
        hash.shouldMatch(Regex("^[0-9a-f]{${HASH_LENGTH}}$"))
    }
}) {
    private companion object {
        private const val HASH_LENGTH = 64
        private const val TEST_SECRET = "test-secret"
        private const val TEST_TOKEN = "test-token"
    }
}

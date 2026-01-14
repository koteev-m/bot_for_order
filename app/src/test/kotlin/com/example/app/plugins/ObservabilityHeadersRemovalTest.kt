package com.example.app.plugins

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpHeaders
import io.ktor.server.response.ResponseHeaders
import java.util.Locale
import org.slf4j.Logger
import io.mockk.mockk

class ObservabilityHeadersRemovalTest : StringSpec({
    "does not append strict header when removal fails" {
        val logger = mockk<Logger>(relaxed = true)
        val removalSupport = HeaderRemovalSupport(logger)
        val headerWriter = HeaderWriter(
            aggressiveReplaceStrictHeaders = true,
            strictHeaders = setOf(HttpHeaders.CacheControl.lowercase(Locale.ROOT)),
            removalSupport = removalSupport,
            logger = logger,
        )
        val headers = TestResponseHeaders(
            initial = mapOf(HttpHeaders.CacheControl to listOf("public, max-age=60")),
            mutableValues = false,
            removeClears = false,
        )

        headerWriter.append(
            headers,
            HttpHeaders.CacheControl,
            "no-store",
        )

        val values = headers.values(HttpHeaders.CacheControl)
        values.size shouldBe 1
        values.first() shouldBe "public, max-age=60"
    }

    "does not append strict header when non-aggressive conflicts exist" {
        val logger = mockk<Logger>(relaxed = true)
        val removalSupport = HeaderRemovalSupport(logger)
        val headerWriter = HeaderWriter(
            aggressiveReplaceStrictHeaders = false,
            strictHeaders = setOf(HttpHeaders.CacheControl.lowercase(Locale.ROOT)),
            removalSupport = removalSupport,
            logger = logger,
        )
        val headers = TestResponseHeaders(
            initial = mapOf(HttpHeaders.CacheControl to listOf("public, max-age=60")),
            mutableValues = false,
            removeClears = false,
        )

        headerWriter.append(
            headers,
            HttpHeaders.CacheControl,
            "no-store",
        )

        val values = headers.values(HttpHeaders.CacheControl)
        values.size shouldBe 1
        values.first() shouldBe "public, max-age=60"
    }
})

private class TestResponseHeaders(
    initial: Map<String, List<String>>,
    private val mutableValues: Boolean,
    private val removeClears: Boolean,
) : ResponseHeaders() {
    private val store = linkedMapOf<String, MutableList<String>>()

    init {
        initial.forEach { (name, values) ->
            store[name.lowercase(Locale.ROOT)] = values.toMutableList()
        }
    }

    fun remove(name: String) {
        if (removeClears) {
            store.remove(name.lowercase(Locale.ROOT))
        }
    }

    override fun engineAppendHeader(name: String, value: String) {
        store.getOrPut(name.lowercase(Locale.ROOT)) { mutableListOf() }.add(value)
    }

    override fun getEngineHeaderNames(): List<String> =
        store.keys.toList()

    override fun getEngineHeaderValues(name: String): List<String> {
        val values = store[name.lowercase(Locale.ROOT)].orEmpty()
        return if (mutableValues) values else values.toList()
    }
}

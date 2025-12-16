package com.example.bots

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.slf4j.LoggerFactory

private class DummyRequest : BaseRequest<DummyRequest, BaseResponse>(BaseResponse::class.java) {
    override fun getMethod(): String = "sendMessage"
}

class InstrumentedTelegramBotTest : StringSpec({
    "increments error counter on failed response" {
        val registry = SimpleMeterRegistry()
        val request = DummyRequest()
        val response = mockk<BaseResponse> {
            every { isOk } returns false
            every { errorCode() } returns 429
            every { description() } returns "Too many requests"
        }
        val delegate = mockk<TelegramBot> {
            every { execute(request) } returns response
        }

        val bot = InstrumentedTelegramBot(
            botLabel = "admin",
            token = "test-token",
            meterRegistry = registry,
            log = LoggerFactory.getLogger("InstrumentedTelegramBotTest"),
            delegate = delegate
        )

        bot.execute(request)

        registry
            .counter("tg.api.requests.total", "method", "sendMessage", "bot", "admin")
            .count() shouldBe 1.0
        registry
            .counter("tg.api.responses.error", "method", "sendMessage", "bot", "admin", "error_code", "429")
            .count() shouldBe 1.0
        registry
            .counter("tg.api.responses.ok", "method", "sendMessage", "bot", "admin")
            .count() shouldBe 0.0
    }

    "increments error counter on exception" {
        val registry = SimpleMeterRegistry()
        val request = DummyRequest()
        val delegate = mockk<TelegramBot> {
            every { execute(request) } throws RuntimeException("boom")
        }

        val bot = InstrumentedTelegramBot(
            botLabel = "shop",
            token = "test-token",
            meterRegistry = registry,
            log = LoggerFactory.getLogger("InstrumentedTelegramBotTest"),
            delegate = delegate
        )

        shouldThrow<RuntimeException> { bot.execute(request) }

        registry
            .counter("tg.api.requests.total", "method", "sendMessage", "bot", "shop")
            .count() shouldBe 1.0
        registry
            .counter(
                "tg.api.responses.error",
                "method",
                "sendMessage",
                "bot",
                "shop",
                "error_code",
                "exception"
            )
            .count() shouldBe 1.0
        registry
            .counter("tg.api.responses.ok", "method", "sendMessage", "bot", "shop")
            .count() shouldBe 0.0
    }
})

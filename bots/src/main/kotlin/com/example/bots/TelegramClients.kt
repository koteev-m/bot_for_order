package com.example.bots

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val TELEGRAM_REQUESTS_METRIC = "tg.api.requests.total"
private const val TELEGRAM_RESPONSES_OK_METRIC = "tg.api.responses.ok"
private const val TELEGRAM_RESPONSES_ERROR_METRIC = "tg.api.responses.error"
private const val TAG_METHOD = "method"
private const val TAG_BOT = "bot"
private const val TAG_ERROR_CODE = "error_code"

class TelegramClients(
    adminToken: String,
    shopToken: String,
    meterRegistry: MeterRegistry?,
    log: Logger = LoggerFactory.getLogger(TelegramClients::class.java)
) {
    val adminBot: InstrumentedTelegramBot = InstrumentedTelegramBot("admin", adminToken, meterRegistry, log)
    val shopBot: InstrumentedTelegramBot = InstrumentedTelegramBot("shop", shopToken, meterRegistry, log)
}

class InstrumentedTelegramBot(
    private val botLabel: String,
    token: String,
    private val meterRegistry: MeterRegistry?,
    private val log: Logger,
    private val delegate: TelegramBot = TelegramBot(token)
) {
    fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: T): R {
        val method = request.method
        meterRegistry?.let { registry ->
            registry.counter(TELEGRAM_REQUESTS_METRIC, Tags.of(TAG_METHOD, method, TAG_BOT, botLabel)).increment()
        }

        val response: R = runCatching { delegate.execute(request) }
            .getOrElse { exception ->
                meterRegistry?.let { registry ->
                    registry.counter(
                        TELEGRAM_RESPONSES_ERROR_METRIC,
                        Tags.of(TAG_METHOD, method, TAG_BOT, botLabel, TAG_ERROR_CODE, "exception")
                    ).increment()
                }
                log.warn(
                    "tg_api outcome=exception bot={} method={} message={}",
                    botLabel,
                    method,
                    sanitizeDescription(exception.message)
                )
                throw exception
            }
        val safeDescription = sanitizeDescription(response.description())
        if (response.isOk) {
            meterRegistry?.let { registry ->
                registry.counter(
                    TELEGRAM_RESPONSES_OK_METRIC,
                    Tags.of(TAG_METHOD, method, TAG_BOT, botLabel)
                ).increment()
            }
            val logOk = System.getenv("TG_API_LOG_OK") == "true"
            if (logOk) {
                log.info("tg_api outcome=ok bot={} method={}", botLabel, method)
            } else {
                log.debug("tg_api outcome=ok bot={} method={}", botLabel, method)
            }
        } else {
            val errorCode = response.errorCode()?.toString() ?: "unknown"
            meterRegistry?.let { registry ->
                registry.counter(
                    TELEGRAM_RESPONSES_ERROR_METRIC,
                    Tags.of(TAG_METHOD, method, TAG_BOT, botLabel, TAG_ERROR_CODE, errorCode)
                ).increment()
            }
            log.warn(
                "tg_api outcome=error bot={} method={} error_code={} description={}",
                botLabel,
                method,
                errorCode,
                safeDescription
            )
        }

        return response
    }

    private fun sanitizeDescription(description: String?): String? {
        return description
            ?.take(120)
            ?.replace(Regex("\\d{5,}"), "***")
    }
}

object AdminGuard {
    fun requireAdmin(userId: Long, allowed: Set<Long>) {
        require(allowed.contains(userId)) { "Forbidden: not an admin" }
    }
}

package com.example.app.observability

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import org.slf4j.MDC

const val REQUEST_ID_MDC_KEY = "requestId"
const val USER_ID_MDC_KEY = "userId"

fun ApplicationCall.requestId(): String? = MDC.get(REQUEST_ID_MDC_KEY) ?: callId

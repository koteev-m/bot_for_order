package com.example.app.security

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import org.slf4j.MDC

private val RBAC_CONTEXT_KEY = AttributeKey<RbacContext>("RbacContext")

data class RbacContext(val principal: RbacPrincipal?)

sealed interface RbacPrincipal {
    val actorId: String
}

data class UserPrincipal(val userId: Long) : RbacPrincipal {
    override val actorId: String = userId.toString()
}

data class TelegramPrincipal(val telegramId: Long) : RbacPrincipal {
    override val actorId: String = telegramId.toString()
}

val ApplicationCall.rbacPrincipal: RbacPrincipal?
    get() = if (attributes.contains(RBAC_CONTEXT_KEY)) {
        attributes[RBAC_CONTEXT_KEY].principal
    } else {
        null
    }

fun ApplicationCall.setRbacPrincipal(principal: RbacPrincipal) {
    if (attributes.contains(RBAC_CONTEXT_KEY)) {
        attributes.remove(RBAC_CONTEXT_KEY)
    }
    attributes.put(RBAC_CONTEXT_KEY, RbacContext(principal))
}

fun ApplicationCall.clearRbacContext() {
    if (attributes.contains(RBAC_CONTEXT_KEY)) {
        attributes.remove(RBAC_CONTEXT_KEY)
    }
}

suspend fun <T> ApplicationCall.withRbacPrincipal(principal: RbacPrincipal, block: suspend () -> T): T {
    val previous = if (attributes.contains(RBAC_CONTEXT_KEY)) {
        attributes[RBAC_CONTEXT_KEY]
    } else {
        null
    }
    setRbacPrincipal(principal)
    val closeable = MDC.putCloseable("actor_id", principal.actorId)
    return try {
        block()
    } finally {
        closeable.close()
        if (previous != null) {
            attributes.remove(RBAC_CONTEXT_KEY)
            attributes.put(RBAC_CONTEXT_KEY, previous)
        } else {
            clearRbacContext()
        }
    }
}

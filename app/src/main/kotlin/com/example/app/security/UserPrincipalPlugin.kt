package com.example.app.security

import io.ktor.server.application.createApplicationPlugin
import org.slf4j.MDC
import kotlinx.coroutines.job

val UserPrincipalPlugin = createApplicationPlugin(name = "UserPrincipalPlugin") {
    onCall { call ->
        if (call.rbacPrincipal != null) {
            return@onCall
        }
        val header = call.request.headers["X-User-Id"] ?: return@onCall
        val userId = header.toLongOrNull() ?: return@onCall
        call.setRbacPrincipal(UserPrincipal(userId))
    }
}

val RbacActorMdcPlugin = createApplicationPlugin(name = "RbacActorMdcPlugin") {
    onCall { call ->
        val principal = call.rbacPrincipal ?: return@onCall
        val closeable = MDC.putCloseable("actor_id", principal.actorId)
        call.coroutineContext.job.invokeOnCompletion {
            closeable.close()
        }
    }
}

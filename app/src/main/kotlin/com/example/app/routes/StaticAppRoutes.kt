package com.example.app.routes

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.installStaticAppRoutes() {
    routing {
        get("/app") {
            call.respondRedirect("/app/")
        }
        get("/app/") {
            val resource = this::class.java.classLoader.getResource("static/app/index.html")
            if (resource != null) {
                call.respondBytes(resource.readBytes(), contentType = ContentType.Text.Html)
            } else {
                call.respondText(
                    "Mini App not built. Run :miniapp:browserDistribution",
                    contentType = ContentType.Text.Plain
                )
            }
        }
        staticResources("/app", "static/app")
    }
}

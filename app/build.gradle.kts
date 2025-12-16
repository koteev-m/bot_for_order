import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.serialization)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.metrics.micrometer)

    implementation(project(":bots"))
    implementation(project(":domain"))
    implementation(project(":infra:db"))
    implementation(project(":infra:redis"))

    implementation(libs.redisson)
    implementation(libs.pengrad)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.logback.encoder)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json.client)
}

ktor {
    fatJar {
        archiveFileName.set("app-all.jar")
    }
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputFile = layout.projectDirectory.file("src/main/resources/build-info.json")
    outputs.file(outputFile)
    outputs.upToDateWhen { false }

    doLast {
        val version = project.version.toString()
        val commit = project.execAndCapture("git", "rev-parse", "--short", "HEAD")
            ?: System.getenv("GIT_COMMIT")
            ?: "unknown"
        val branch = project.execAndCapture("git", "rev-parse", "--abbrev-ref", "HEAD")
            ?: System.getenv("GIT_BRANCH")
            ?: "unknown"
        val builtAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC))
        val payload = """{"version":"$version","commit":"$commit","branch":"$branch","builtAt":"$builtAt"}"""
        outputFile.asFile.apply {
            parentFile.mkdirs()
            writeText(payload + "\n")
        }
    }
}

tasks.named("processResources") {
    dependsOn(generateBuildInfo)
    dependsOn(":miniapp:copyMiniAppToApp")
}

fun Project.execAndCapture(vararg command: String): String? {
    val buffer = ByteArrayOutputStream()
    return runCatching {
        exec {
            commandLine(*command)
            standardOutput = buffer
        }
        buffer.toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}

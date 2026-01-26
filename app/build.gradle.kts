import java.io.ByteArrayOutputStream
import org.gradle.api.tasks.Copy
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

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
    implementation(libs.aws.sdk.s3)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.logback.encoder)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.postgresql)
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
    val outputFile = layout.buildDirectory.file("generated-resources/build/build-info.json")
    outputs.file(outputFile)
    // Гарантируем, что build-info.json всегда пересоздается
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
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(payload + "\n")
        }
    }
}

tasks.named<Copy>("processResources") {
    val buildInfo = layout.buildDirectory.file("generated-resources/build/build-info.json")
    dependsOn(generateBuildInfo)
    from(buildInfo) {
        rename { "build-info.json" }
    }
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

// --- Hook miniapp only into packaging tasks, not into tests ---
val skipMiniappCopy: Boolean =
    (providers.gradleProperty("skipMiniappCopy").orNull?.toBoolean()) ?: false

fun hookMiniappTo(vararg names: String) {
    val wanted = names.toSet()
    tasks.matching { it.name in wanted }.configureEach {
        if (skipMiniappCopy) {
            logger.lifecycle("miniapp copy is disabled: -PskipMiniappCopy=true (task='${this.name}')")
            return@configureEach
        }
        val miniapp = rootProject.findProject(":miniapp")
        if (miniapp != null) {
            // Проверяем, что задача в miniapp действительно есть — если нет, Gradle просто проигнорирует dependsOn
            dependsOn(":miniapp:copyMiniAppToApp")
        } else {
            logger.lifecycle("miniapp module not found — skipping hook for task '${this.name}'")
        }
    }
}

// «толстый» JAR от Ktor-плагина
hookMiniappTo("fatJar")
// Обычные дистрибутивные задачи
hookMiniappTo("jar", "installDist", "distZip", "distTar")
// Shadow JAR, если присутствует
hookMiniappTo("shadowJar")
// Дополнительные dist-задачи (если присутствуют)
hookMiniappTo("assembleDist", "installShadowDist", "shadowDistZip", "shadowDistTar")

// --- Testing quality of life ---
tasks.withType<Test>().configureEach {
    // Kotest работает через JUnit Platform — явно зафиксируем
    useJUnitPlatform()
    // Чуть более информативный вывод локально и в CI
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Отдельная задача: только MetricsSecurityTest
tasks.register<Test>("metricsSecurityTest") {
    group = "verification"
    description = "Run MetricsSecurityTest only"
    useJUnitPlatform()
    val base = tasks.named<Test>("test").get()
    testClassesDirs = base.testClassesDirs
    classpath = base.classpath
    filter {
        includeTestsMatching("com.example.app.MetricsSecurityTest")
        // Не помечаем build как failed, если ничего не найдено по шаблону
        // (например, при переименовании файла) — по желанию, можно убрать:
        isFailOnNoMatchingTests = false
    }
}

// Отдельная задача: все тесты резолвера IP
tasks.register<Test>("clientIpResolverTests") {
    group = "verification"
    description = "Run ClientIp* tests"
    useJUnitPlatform()
    val base = tasks.named<Test>("test").get()
    testClassesDirs = base.testClassesDirs
    classpath = base.classpath
    filter {
        includeTestsMatching("com.example.app.ClientIp*Test*")
        isFailOnNoMatchingTests = false
    }
}

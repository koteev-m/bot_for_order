import io.gitlab.arturbosch.detekt.Detekt

plugins {
    kotlin("js")
    alias(libs.plugins.serialization)
    alias(libs.plugins.detekt)
}

repositories {
    mavenCentral()
}

val chromeBin = providers.environmentVariable("CHROME_BIN").orNull
val runDetekt = providers.environmentVariable("RUN_DETEKT").orNull == "true"

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "miniapp.js"
                cssSupport {
                    enabled.set(true)
                }
            }
            testTask {
                enabled = !chromeBin.isNullOrBlank()
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        nodejs {
            testTask {
                useMocha()
            }
        }
        binaries.executable()
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    implementation(libs.kvision)
    implementation(libs.kvision.bootstrap)
    implementation(libs.kvision.bootstrap.css)
    implementation(libs.kvision.tom.select)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.js)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json.client)

    testImplementation(kotlin("test"))

    detektPlugins(libs.detekt.formatting)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(
        files(
            "$rootDir/config/detekt/detekt.yml",
            "$projectDir/detekt.yml"
        )
    )
    baseline = file("$projectDir/detekt-baseline.xml")
    source.from(files("src/main/kotlin"))
}

val copyMiniAppToApp by tasks.registering(Copy::class) {
    dependsOn(tasks.named("browserDistribution"))
    from(layout.buildDirectory.dir("distributions"))
    into(layout.projectDirectory.dir("../app/src/main/resources/static/app"))
}

tasks.register("kotlinNpmInstall") {
    group = "build"
    description = "Installs Node.js toolchain required for Kotlin/JS"
    dependsOn(tasks.named("kotlinNodeJsSetup"))
}

tasks.register("kotlinUpgradeYarnLock") {
    group = "build"
    description = "Stub compatibility task for yarn.lock upgrades"
    doLast {
        logger.lifecycle("kotlinUpgradeYarnLock: no yarn.lock updates required")
    }
}


tasks.withType<Detekt>().configureEach {
    enabled = runDetekt
}

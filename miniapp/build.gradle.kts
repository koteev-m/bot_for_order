plugins {
    kotlin("js")
    alias(libs.plugins.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "miniapp.js"
                cssSupport {
                    enabled.set(true)
                }
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
}

val copyMiniAppToApp by tasks.registering(Copy::class) {
    dependsOn(tasks.named("browserDistribution"))
    from(layout.buildDirectory.dir("distributions"))
    into(layout.projectDirectory.dir("../app/src/main/resources/static/app"))
}

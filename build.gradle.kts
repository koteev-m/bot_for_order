import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.js) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

allprojects {
    group = "com.example"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }
    }

    detekt {
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
        config.setFrom(files("$rootDir/detekt.yml"))
    }
}

ktlint {
    version.set(libs.versions.ktlint.get())
    android.set(false)
    verbose.set(true)
}

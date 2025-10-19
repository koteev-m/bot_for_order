plugins {
    alias(libs.plugins.kotlin.jvm)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    implementation(project(":domain"))

    api(libs.exposed.core)
    api(libs.exposed.dao)
    api(libs.exposed.javatime)
    api(libs.exposed.jdbc)

    implementation(libs.postgresql)
    api(libs.flyway.core)
    api(libs.hikari)
}

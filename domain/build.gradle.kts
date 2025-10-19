plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
}

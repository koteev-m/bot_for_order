plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.pengrad)
    implementation(libs.serialization.json)
    implementation(libs.micrometer.core)
    implementation(libs.slf4j.api)
}

plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.slf4j)
    implementation(libs.redisson)
    implementation(libs.serialization.json)
    implementation(project(":domain"))
}

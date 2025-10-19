plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
}

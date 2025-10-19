plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    api(libs.redisson)
}

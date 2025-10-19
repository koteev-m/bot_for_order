plugins { alias(libs.plugins.kotlin.js) }

kotlin {
    js(IR) {
        binaries.executable()
        browser()
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
}

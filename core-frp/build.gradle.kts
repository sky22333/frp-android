plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sky22333.frpandroid.core.frp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tomlj)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

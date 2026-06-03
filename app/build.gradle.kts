plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = providers.environmentVariable("APP_VERSION_NAME").orElse("v0.0.1").get()

android {
    namespace = "com.sky22333.frpandroid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.sky22333.frpandroid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = appVersionName
    }

    flavorDimensions += "abi"

    productFlavors {
        create("universal") {
            dimension = "abi"
        }
        create("arm64V8a") {
            dimension = "abi"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("armeabiV7a") {
            dimension = "abi"
            ndk {
                abiFilters += "armeabi-v7a"
            }
        }
        create("x86_64") {
            dimension = "abi"
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = providers.environmentVariable("SIGNING_KEY_FILE").orNull
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val keystorePath = providers.environmentVariable("SIGNING_KEY_FILE").orNull
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    "universalImplementation"(files("libs/universal/frplib-universal.aar"))
    "arm64V8aImplementation"(files("libs/arm64-v8a/frplib-arm64-v8a.aar"))
    "armeabiV7aImplementation"(files("libs/armeabi-v7a/frplib-armeabi-v7a.aar"))
    "x86_64Implementation"(files("libs/x86_64/frplib-x86_64.aar"))

    implementation(project(":core-frp"))
    implementation(project(":core-runtime"))
    implementation(project(":core-data"))
    implementation(project(":core-ui"))
    implementation(project(":feature-dashboard"))
    implementation(project(":feature-profiles"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-logs"))
    implementation(project(":feature-settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
}

import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The app's version comes from the repository's VERSION file, like the Switch build.
val appVersion = File(rootDir.parentFile, "VERSION").takeIf { it.exists() }?.readLines()?.firstOrNull()?.trim() ?: "0.0.0"
val versionParts = appVersion.split(".").mapNotNull { it.toIntOrNull() }

android {
    namespace = "app.compendium.android"
    compileSdk = 35
    // Pinned so CI and local builds use the same toolchain.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.compendium.android"
        minSdk = 26
        targetSdk = 35
        versionCode = (versionParts.getOrElse(0) { 0 } * 10000) + (versionParts.getOrElse(1) { 0 } * 100) + versionParts.getOrElse(2) { 0 } + 1
        versionName = appVersion
        buildConfigField("String", "APP_VERSION", "\"$appVersion\"")
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += "-std=c++20"
            }
        }
    }

    // CI signs with a keystore from secrets; a local build falls back to the debug key.
    signingConfigs {
        val ks = System.getenv("COMPENDIUM_KEYSTORE")
        if (ks != null && File(ks).exists()) {
            create("release") {
                storeFile = File(ks)
                storePassword = System.getenv("COMPENDIUM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COMPENDIUM_KEY_ALIAS")
                keyPassword = System.getenv("COMPENDIUM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}

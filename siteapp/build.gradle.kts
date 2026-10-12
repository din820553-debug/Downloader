plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingFile = System.getenv("DOWNLOADER_KEYSTORE_FILE")?.takeIf(String::isNotBlank)

android {
    // Reuse the downloader core and its resources, while shipping an independent launcher,
    // package name, and per-app session. This is not an update of Downloader HYBRID.
    namespace = "com.dindownloader.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dindownloader.sitevideo"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("../app/src/main/java")
            res.srcDirs("../app/src/main/res")
        }
    }
    signingConfigs {
        if (signingFile != null) {
            create("stableRelease") {
                storeFile = file(signingFile)
                storePassword = System.getenv("DOWNLOADER_KEYSTORE_PASSWORD")
                keyAlias = "downloader"
                keyPassword = System.getenv("DOWNLOADER_KEYSTORE_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (signingFile != null) signingConfig = signingConfigs.getByName("stableRelease")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (signingFile != null) signingConfig = signingConfigs.getByName("stableRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingFile = System.getenv("DOWNLOADER_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.dindownloader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dindownloader.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "2.0.3"
        ndk {
            abiFilters += listOf("arm64-v8a")
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
        debug {
            // Test builds must never collide with an installed production app.
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "Downloader BETA"
            if (signingFile != null) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["appLabel"] = "Downloader"
            if (signingFile != null) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
}

android {
    namespace = "app.konspektor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.konspektor"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"
    }

    buildTypes {
        release {
            // R8 shrinks Compose from ~14 MB to a few MB. Signed with the same (debug) key as the
            // earlier builds, so it installs over them and keeps the key and recordings.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { test ->
            listOf("konspektor.tokenFile", "konspektor.repo").forEach { key ->
                project.findProperty(key)?.let { test.systemProperty(key, it.toString()) }
            }
            test.testLogging { showStandardStreams = true }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Jetpack Compose + Material 3 (design reference: android/compose-samples, android/nowinandroid)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    // Frosted-glass blur (chrisbanes/haze) and animations (airbnb/lottie-android)
    implementation(libs.haze)
    implementation(libs.lottie.compose)

    // GitHub client test against the real repository (skipped without a token file)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

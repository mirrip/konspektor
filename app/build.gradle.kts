plugins {
    id("com.android.application")
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
        versionCode = 4
        versionName = "0.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

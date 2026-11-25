plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.onur.statusbartop"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.onur.statusbartop"
        minSdk = 30
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
    }

    // Feste Signatur (liegt im Repo), damit Updates ueber die alte Installation moeglich sind.
    // Nur fuer die private Nutzung gedacht -> Repo auf "privat" stellen.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

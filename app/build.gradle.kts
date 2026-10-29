plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key of the app. The same key must sign every version, otherwise Android refuses the update.
// Values can be overridden by environment variables (e.g. GitHub secrets).
val fwdKeyFile: String = System.getenv("FWD_KEYSTORE_FILE") ?: "armana-forwarder.jks"
val fwdKeyPass: String = System.getenv("FWD_KEYSTORE_PASSWORD") ?: "ArmanaFwd-2026-Key"
val fwdKeyAlias: String = System.getenv("FWD_KEY_ALIAS") ?: "armana-forwarder"

android {
    namespace = "ir.armana.forwarder"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.armana.forwarder"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(fwdKeyFile)
            storePassword = fwdKeyPass
            keyAlias = fwdKeyAlias
            keyPassword = fwdKeyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Only the Kotlin standard library: the app uses the Android framework directly (small, no extra libraries).
}

// Glasses-side CustomApp (CXR-S). Runs on the Rokid glasses, is installed and started by the phone
// app over CXR-L (appUploadAndInstall / appStart), and only displays frames from the phone and
// reports button/touchpad input. It never connects to Home Assistant and never holds credentials.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// CXR-S version paired with client-l 1.0.4 in the official samples (CXRSWithCXRLSample).
val cxrsVersion: String = providers.gradleProperty("rokid.cxrs.version").get()

// Same fixed debug key as the phone app (see app/build.gradle.kts): updates must keep one signature.
val debugKeystore: String? = System.getenv("ROKIDHA_DEBUG_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
fun envOr(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: default

android {
    namespace = "tw.rokidha.glasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.rokidha.glasses" // must equal the phone's CUSTOMAPP package name
        minSdk = 31 // official sample value; glasses run Android 12 (YodaOS-Sprite)
        targetSdk = 35
        versionCode = providers.gradleProperty("rokidha.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("rokidha.versionName").orNull ?: "0.1.0-dev"
    }

    signingConfigs {
        if (debugKeystore != null) {
            getByName("debug") {
                storeFile = file(debugKeystore)
                storePassword = envOr("ROKIDHA_DEBUG_KEYSTORE_PASSWORD", "android")
                keyAlias = envOr("ROKIDHA_DEBUG_KEY_ALIAS", "androiddebugkey")
                keyPassword = envOr("ROKIDHA_DEBUG_KEY_PASSWORD", "android")
            }
        }
    }
    buildTypes {
        // The debug variant is the one bundled into the phone app and sent to the glasses over
        // Bluetooth, so shrink it: R8 removes unused Kotlin/stdlib code (6.7 MB of dex unshrunk).
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Compress native libraries inside the APK: smaller transfer over Bluetooth.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":glassesproto"))
    implementation("com.rokid.cxr:cxr-service-bridge:$cxrsVersion")
}

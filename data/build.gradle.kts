// Android implementations of homeassistant ports: Android Keystore cipher,
// SharedPreferences store, NsdManager (mDNS/DNS-SD) discovery.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tw.rokidha.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":homeassistant"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Real Android Keystore test; needs a device/emulator: ./gradlew :data:connectedAndroidTest
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

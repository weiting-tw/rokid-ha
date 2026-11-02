// Rokid adapter. The ONLY module allowed to depend on com.rokid.cxr:* classes.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val cxrlVersion: String = providers.gradleProperty("rokid.cxrl.version").get()

android {
    namespace = "tw.rokidha.rokid"
    compileSdk = 35

    defaultConfig {
        // Official CXR-L docs require minSdk 31 (client-l AAR itself declares 28).
        minSdk = 31
        buildConfigField("String", "CXRL_VERSION", "\"$cxrlVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":domain"))
    implementation(project(":glassesproto"))
    implementation(libs.kotlinx.coroutines.android)
    // Rokid CXR-L phone-side SDK. Pulls com.rokid.cxr:cxr-service-bridge (snapshot coordinate,
    // see docs/SDK_RESEARCH.md §6) which ships the native libs (arm64-v8a / armeabi-v7a only).
    implementation("com.rokid.cxr:client-l:$cxrlVersion")
    testImplementation(libs.junit)
}

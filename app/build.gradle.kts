plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes ONLY from environment variables (set by CI from GitHub Secrets, or
// locally by the developer). No keystore, password or alias is ever stored in the repository.
// Without them, `assembleRelease` produces an UNSIGNED APK (app-release-unsigned.apk).
val releaseKeystore: String? = System.getenv("ROKIDHA_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseKeystore != null && file(releaseKeystore).exists() &&
    listOf("ROKIDHA_KEYSTORE_PASSWORD", "ROKIDHA_KEY_ALIAS", "ROKIDHA_KEY_PASSWORD").all { !System.getenv(it).isNullOrEmpty() }

// Fixed DEBUG signing key (optional). Android only installs an update over an existing app when
// both are signed with the same key; the default debug key is generated per machine, so every CI
// runner (and every developer) would produce a different signature. CI decodes the shared debug
// keystore from the ANDROID_DEBUG_KEYSTORE_BASE64 secret. Password/alias default to the standard
// Android debug values. Without the variable, the machine's default debug key is used.
val debugKeystore: String? = System.getenv("ROKIDHA_DEBUG_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
fun envOr(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: default

android {
    namespace = "tw.rokidha.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.rokidha.app"
        minSdk = 31
        targetSdk = 35
        // CI passes these from the git tag (release.yml); local builds use the defaults.
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
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("ROKIDHA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ROKIDHA_KEY_ALIAS")
                keyPassword = System.getenv("ROKIDHA_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// The glasses CustomApp (CXR-S) is bundled as an asset; the phone installs it on the glasses
// through the Rokid app (CXR-L appUploadAndInstall) — no ADB needed. Always the debug variant,
// signed with the fixed debug key when ROKIDHA_DEBUG_KEYSTORE_FILE is set.
val glassesApkDir = layout.buildDirectory.dir("generated/glassesApk")
val bundleGlassesApk = tasks.register<Copy>("bundleGlassesApk") {
    dependsOn(":glassesapp:assembleDebug")
    from(rootProject.file("glassesapp/build/outputs/apk/debug/glassesapp-debug.apk"))
    into(glassesApkDir.map { it.dir("glasses") })
    rename { "rokid-ha-glasses.apk" }
}
android.sourceSets["main"].assets.srcDir(glassesApkDir)
tasks.named("preBuild") { dependsOn(bundleGlassesApk) }

dependencies {
    implementation(project(":rokid"))
    implementation(project(":domain"))
    implementation(project(":homeassistant"))
    implementation(project(":presentation"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

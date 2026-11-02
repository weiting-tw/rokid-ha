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

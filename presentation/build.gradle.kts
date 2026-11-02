// Glasses UI bridge: turns Home Assistant state into protocol-neutral DisplayScreens.
// Pure Kotlin/JVM: sees neither Android, nor com.rokid.*, nor credentials.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":domain"))
    api(project(":homeassistant"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(testFixtures(project(":homeassistant")))
}

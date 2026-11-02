// Home Assistant client: REST, WebSocket, connection management, discovery parsing,
// credential storage logic, entity selection and service allowlist.
// Pure Kotlin/JVM. MUST NOT depend on any com.rokid.* class or on Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    // Fake Home Assistant server + in-memory stores, shared with :presentation tests.
    testFixturesApi(libs.okhttp.mockwebserver)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

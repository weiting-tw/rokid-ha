// Pure Kotlin module: no Android, no Rokid SDK, no Home Assistant client.
// Holds the ports (interfaces) the rest of the system talks to.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Target Java 17 bytecode without pinning a toolchain (builds on JDK 17 or newer).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

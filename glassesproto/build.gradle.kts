// Phone <-> glasses CustomApp message format, shared by :rokid (phone) and :glassesapp (glasses).
// Pure Kotlin: no Android, no Rokid classes (both sides map fields to com.rokid.cxr.Caps).
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
    testImplementation(libs.junit)
}

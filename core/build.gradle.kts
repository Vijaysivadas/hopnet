// Pure Kotlin/JVM: the hopping engine, wire protocol and server client. No Android imports,
// so routing can be tested (and simulated across many nodes) on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lazysodium.java)
}

tasks.test {
    // Live end-to-end test against a running server is opt-in:
    //   HOPNET_LIVE_SERVER=http://127.0.0.1:8000 ./gradlew :core:test
    environment("HOPNET_LIVE_SERVER", System.getenv("HOPNET_LIVE_SERVER") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

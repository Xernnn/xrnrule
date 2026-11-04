import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Java 17 bytecode and API, built by whatever JDK runs Gradle (17 or newer): no toolchain to find.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

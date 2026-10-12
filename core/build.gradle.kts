import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin, no Android: link parsing, the media API parsers and the GIF size search, so they can be unit tested on the JVM.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

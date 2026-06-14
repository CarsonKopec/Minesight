pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle auto-download the pinned JDK vendor (Temurin) for the toolchain.
// 1.0.0+ is required for Gradle 9 (older versions reference the removed
// JvmVendorSpec.IBM_SEMERU).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "minesight-client"

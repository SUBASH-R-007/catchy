pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions a Java 21 toolchain automatically when none is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cachelab"

include("cache-core", "cache-bench", "cache-spring", "cache-server", "formulary-service")

project(":formulary-service").projectDir = file("examples/formulary-service")

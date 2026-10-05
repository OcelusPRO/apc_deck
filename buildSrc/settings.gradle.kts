dependencyResolutionManagement {
    
    // Use Maven Central and the Gradle Plugin Portal for resolving dependencies in the shared build logic (`buildSrc`) project.
    @Suppress("UnstableApiUsage") repositories {
        mavenCentral()
        gradlePluginPortal() // plugin Animal Sniffer (convention android-compatible)
        if (providers.gradleProperty("android").orNull == "true") google() // Android Gradle Plugin
    }
    
    // Reuse the version catalog from the main build.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"
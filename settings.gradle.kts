// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement { // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage") repositories {
        mavenCentral()
        google()
    }
}

plugins { // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// api : jar publié aux auteurs de plugins · core : moteur · app : interface Compose (+ navigateur intégré)
// plugins:macros : plugin Macros, compilé comme un plugin tiers (jar à glisser dans l'interface)
include(":api")
include(":core")
include(":app")
include(":plugins:macros")
include(":plugins:synth")

rootProject.name = "MIDI_streamdeck"

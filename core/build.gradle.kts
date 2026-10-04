plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.android-compatible") // aussi compilé dans l'app Android
    id("buildsrc.convention.web-ui") // interface React + Tailwind (dossier web/ ou ui/)
    `java-library`
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    api(project(":api"))
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
}

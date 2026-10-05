// Implémentations PC du cœur : MIDI (javax.sound.midi), son (javax.sound.sampled), plugins en jars JVM,
// mises à jour (releases GitHub, installeurs), ouverture de dossiers.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-library`
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
}

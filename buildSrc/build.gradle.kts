plugins { // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

dependencies { // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.animalSnifferGradlePlugin)
    // App Android (-Pandroid=true) : l'Android Gradle Plugin doit être chargé avec le plugin Kotlin (même
    // classloader), sinon le Kotlin intégré d'AGP ne trouve pas ses classes. Absent des builds PC (pas de dépôt Google).
    if (providers.gradleProperty("android").orNull == "true") implementation(libs.androidGradlePlugin)
}

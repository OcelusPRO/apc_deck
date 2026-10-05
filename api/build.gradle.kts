// API publique des plugins : seule dépendance (compileOnly) d'un plugin tiers.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.android-compatible") // aussi compilé dans l'app Android
    `java-library`
}

dependencies {
    // Fournies par l'application à l'exécution : les plugins ne doivent pas les embarquer.
    api(libs.kotlinxCoroutines)
}

tasks.jar {
    archiveBaseName = "apcdeck-api"
}

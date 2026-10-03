// API publique des plugins : seule dépendance (compileOnly) d'un plugin tiers.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    `java-library`
}

dependencies {
    // Fournies par l'application à l'exécution : les plugins ne doivent pas les embarquer.
    api(libs.kotlinxCoroutines)
}

tasks.jar {
    archiveBaseName = "apcdeck-api"
}

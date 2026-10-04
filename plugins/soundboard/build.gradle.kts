// Plugin Soundboard, construit comme un plugin tiers : seule l'API est visible (compileOnly).
// Son : javax.sound.sampled (inclus dans Java). Jar produit : plugins/soundboard/build/libs/apcdeck-soundboard.jar
plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.web-ui") // interface React + Tailwind (dossier web/ ou ui/)
    id("buildsrc.convention.official-plugin") // version de l'application, publié dans ses releases
}

dependencies {
    compileOnly(project(":api"))
    testImplementation(project(":api"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    archiveBaseName = "apcdeck-soundboard"
}

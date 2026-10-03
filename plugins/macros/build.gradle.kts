// Plugin Macros, construit comme un plugin tiers : seule l'API est visible (compileOnly).
// Jar produit : plugins/macros/build/libs/apcdeck-macros.jar -> à glisser dans l'interface.
// Son interface web (React + Tailwind) est dans web/ ; compilée par Vite et copiée dans le jar sous web/.
plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.web-ui") // interface React + Tailwind (dossier web/ ou ui/)
}

dependencies {
    compileOnly(project(":api"))
    testImplementation(project(":api"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    archiveBaseName = "apcdeck-macros"
}

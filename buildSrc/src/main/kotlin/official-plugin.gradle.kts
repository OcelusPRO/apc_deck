// Plugin officiel : livré avec l'application et publié dans ses releases (sous le nom <id>.jar), au même numéro de
// version qu'elle. Dans src/main/resources/plugin.json, "version": "${version}" est remplacé par appVersion
// (gradle.properties, ou -PappVersion=… dans le workflow de release).
package buildsrc.convention

plugins {
    java
}

tasks.named<ProcessResources>("processResources") {
    // Variable locale : la closure ne doit pas capturer le script (cache de configuration).
    val appVersion: String = providers.gradleProperty("appVersion").get()
    inputs.property("appVersion", appVersion)
    filesMatching("plugin.json") { expand("version" to appVersion) }
}

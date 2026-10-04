// Module partagé avec l'app Android (api, core, plugins officiels) : son bytecode ne doit utiliser que des API
// présentes sur Android au niveau minSdk (java.* compris), vérifié par Animal Sniffer à chaque `check`.
package buildsrc.convention

plugins {
    java
    id("ru.vyarus.animalsniffer")
}

dependencies {
    // Android 8.0 (API 26) = minSdk de l'app Android.
    "signature"("net.sf.androidscents.signature:android-api-level-26:8.0.0_r2@signature")
}

animalsniffer {
    sourceSets = listOf(project.the<SourceSetContainer>()["main"])
}

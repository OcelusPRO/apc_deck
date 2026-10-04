// Application : moteur + serveur local de l'interface web + icône de la zone de notification.
//
// Distribution (jpackage ne fait pas de compilation croisée : chaque OS produit ses propres exécutables) :
//   gradlew :app:packageApp        archive portable de l'OS courant, Java embarqué (zip / tar.gz) -> build/dist
//   gradlew :app:packageInstaller  installeur de l'OS courant : .exe (Windows, WiX requis), .deb, .dmg -> build/dist
//   gradlew :app:packageJar        jar unique multi-plateforme (Java 25 requis pour le lancer)  -> build/dist
// Windows + Linux + macOS d'un coup : workflow GitHub Actions .github/workflows/package.yml
plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

val appName = "APCDeck"
/** Version (gradle.properties, ou -PappVersion=… : le workflow y met le numéro du build). Sert à la recherche de mises à jour. */
val appVersion: String = providers.gradleProperty("appVersion").get()
version = appVersion

/** Plugins livrés avec l'application (copiés dans plugins/ au premier lancement). */
val bundledPlugins: Configuration = configurations.create("bundledPlugins") { isTransitive = false }

dependencies {
    implementation(project(":core"))
    bundledPlugins(project(":plugins:macros"))
    bundledPlugins(project(":plugins:synth"))
    bundledPlugins(project(":plugins:soundboard"))
}

application {
    mainClass = "fr.ftnl.apcdeck.app.MainKt"
    applicationName = appName
}

// Nom fixe : c'est le --main-jar de jpackage.
tasks.jar {
    archiveFileName = "app.jar"
    // Lue au démarrage (Package.implementationVersion) pour comparer avec les releases GitHub.
    manifest { attributes("Implementation-Version" to appVersion) }
}

tasks.named<JavaExec>("run") {
    // En développement, l'application travaille dans <projet>/run (plugins, config, données).
    systemProperty("apcdeck.home", rootDir.resolve("run").absolutePath)
}

// --- distribution -------------------------------------------------------------------

enum class Os(val id: String, val imageType: String, val installerType: String, val icon: String, val archive: String) {
    WINDOWS("windows", "app-image", "exe", "icon.ico", "zip"),
    LINUX("linux", "app-image", "deb", "icon.png", "tar.gz"),
    MACOS("macos", "app-image", "dmg", "icon.icns", "tar.gz"),
}

val os: Os = System.getProperty("os.name").lowercase().let {
    when {
        "win" in it -> Os.WINDOWS
        "mac" in it -> Os.MACOS
        else -> Os.LINUX
    }
}
val arch: String = System.getProperty("os.arch").let { if (it == "amd64" || it == "x86_64") "x64" else it }

/** jpackage du JDK de la toolchain (même version que la compilation). */
val jpackage: Provider<String> = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    .map { it.metadata.installationPath.file("bin/jpackage" + if (os == Os.WINDOWS) ".exe" else "").asFile.absolutePath }

val jpackageDir = layout.buildDirectory.dir("jpackage")
val distDir = layout.buildDirectory.dir("dist")
val libDir = layout.buildDirectory.dir("install/$appName/lib")
val iconFile = layout.projectDirectory.file("packaging/${os.icon}")

/** Modules Java embarqués (calculés avec jdeps --print-module-deps). */
val javaModules = "java.base,java.desktop,java.instrument,jdk.httpserver,jdk.unsupported"

val copyBundledPlugins = tasks.register<Sync>("copyBundledPlugins") {
    from(bundledPlugins)
    into(jpackageDir.map { it.dir("content/bundled-plugins") })
}

val jpackageImage = tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Image de l'application pour l'OS courant (exécutable + Java embarqué)."
    dependsOn(tasks.installDist, copyBundledPlugins)
    val output = jpackageDir.get().dir("image").asFile
    val content = jpackageDir.get().dir("content/bundled-plugins").asFile
    inputs.dir(libDir)
    inputs.dir(content)
    inputs.property("version", appVersion)
    outputs.dir(output)
    doFirst {
        // jpackage crée l'exe en lecture seule (impossible à supprimer tel quel sous Windows) ; l'antivirus peut
        // aussi le garder ouvert un instant : on rend tout inscriptible et on réessaie.
        repeat(20) {
            if (!output.exists()) return@doFirst
            output.walkBottomUp().forEach { it.setWritable(true) }
            if (output.deleteRecursively()) return@doFirst
            Thread.sleep(500)
        }
        error("impossible de vider $output (fichier verrouillé ?)")
    }
    executable = jpackage.get()
    args(
        "--type", os.imageType,
        "--name", appName,
        "--app-version", appVersion,
        "--vendor", "ftnl",
        "--description", "Cadre à plugins pour l'AKAI APC Key 25 mk2",
        "--input", libDir.get().asFile.absolutePath,
        "--main-jar", "app.jar",
        "--main-class", "fr.ftnl.apcdeck.app.MainKt",
        "--add-modules", javaModules,
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        "--app-content", content.absolutePath,
        "--java-options", "-Dapcdeck.bundled=\$APPDIR/../bundled-plugins",
        "--icon", iconFile.asFile.absolutePath,
        "--dest", output.absolutePath,
    )
}

/** Plugins officiels pour la release : <id>.jar, le nom que cherche la mise à jour des plugins (Updater). */
tasks.register<Copy>("packagePlugins") {
    group = "distribution"
    description = "Jars des plugins livrés, nommés <id>.jar, pour la release GitHub."
    from(bundledPlugins)
    into(distDir)
    rename { it.removePrefix("apcdeck-") }
}

fun AbstractArchiveTask.portableArchive() {
    group = "distribution"
    description = "Archive portable pour l'OS courant (rien à installer, Java embarqué)."
    from(jpackageImage)
    destinationDirectory = distDir
    archiveFileName = "$appName-$appVersion-${os.id}-$arch.${os.archive}"
}

// zip sous Windows ; tar.gz ailleurs (conserve les droits d'exécution).
if (os == Os.WINDOWS) {
    tasks.register<Zip>("packageApp") { portableArchive() }
} else {
    tasks.register<Tar>("packageApp") {
        portableArchive()
        compression = Compression.GZIP
    }
}

tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    description = "Installeur pour l'OS courant : exe (Windows, WiX requis), deb ou dmg."
    dependsOn(jpackageImage)
    val image = jpackageDir.get().dir("image").asFile.resolve(if (os == Os.MACOS) "$appName.app" else appName)
    val output = distDir.get().asFile
    // Entrées déclarées : sans elles Gradle croit l'installeur à jour et garde un ancien .exe.
    inputs.dir(image)
    inputs.property("version", appVersion)
    val installer = output.resolve(when (os) {
        Os.WINDOWS -> "$appName-$appVersion.exe"
        Os.MACOS -> "$appName-$appVersion.dmg"
        Os.LINUX -> "${appName.lowercase()}_${appVersion}_amd64.deb"
    })
    outputs.file(installer)
    // jpackage n'écrase pas l'installeur précédent (créé en lecture seule) : on le retire d'abord.
    doFirst { if (installer.exists()) installer.setWritable(true) && installer.delete() }
    executable = jpackage.get()
    args(
        "--type", os.installerType,
        "--app-image", image.absolutePath,
        "--name", appName,
        "--app-version", appVersion,
        "--vendor", "ftnl",
        "--dest", output.absolutePath,
    )
    when (os) {
        Os.WINDOWS -> args("--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install",
            "--win-upgrade-uuid", "6f7b0a7e-4c1d-4f55-9a5e-3c2f1a8d9b10")
        Os.LINUX -> args("--linux-shortcut", "--linux-menu-group", "AudioVideo")
        Os.MACOS -> {}
    }
}

tasks.register<Jar>("packageJar") {
    group = "distribution"
    description = "Jar unique multi-plateforme (java -jar ; Java 25 requis)."
    archiveFileName = "$appName-$appVersion-all.jar"
    destinationDirectory = distDir
    manifest { attributes("Main-Class" to "fr.ftnl.apcdeck.app.MainKt", "Implementation-Version" to appVersion) }
    from(sourceSets.main.map { it.output })
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from(runtime.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

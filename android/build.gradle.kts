// Application Android : le même moteur et la même interface que sur PC (modules core, plugins Synthé et Soundboard),
// avec le MIDI USB, le son et les plugins dex d'Android. Incluse seulement avec le SDK Android :
//   gradlew -Pandroid=true :android:assembleDebug      APK de développement -> build/outputs/apk/debug
//   gradlew -Pandroid=true :android:assembleRelease    APK signé (variables APCDECK_KEYSTORE…) -> build/outputs/apk/release
import javax.inject.Inject

plugins {
    id("com.android.application")
}

val appVersion: String = providers.gradleProperty("appVersion").get()

/** 1.2.34 -> 1_002_034 : croît à chaque release (le dernier nombre est le numéro du build). */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = (version.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "fr.ftnl.apcdeck.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.ftnl.apcdeck"
        minSdk = 26 // java.nio.file, java.time (et API vérifiée par la convention android-compatible)
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    // Signature des releases : clé fournie par la CI (secrets), sinon APK release non signé.
    val keystore = System.getenv("APCDECK_KEYSTORE")?.takeIf { it.isNotBlank() }?.let(::file)
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("APCDECK_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("APCDECK_KEY_ALIAS")
            keyPassword = System.getenv("APCDECK_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // plugins chargés par réflexion, sérialisation : pas de réduction du code
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // plugin.json et web/ des plugins embarqués : servis depuis les assets (un dossier par plugin).
            excludes += listOf("plugin.json", "web/**", "META-INF/versions/9/previous-compilation-data.bin", "META-INF/*.kotlin_module",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

/** Plugins officiels compilés dans l'app : leurs classes en dépendance, leur plugin.json et leur page web en assets. */
val bundledPlugins = mapOf("synth" to ":plugins:synth", "soundboard" to ":plugins:soundboard")

/** Copie plugin.json et web/ de chaque plugin embarqué dans <sortie>/plugins/<id>/. */
abstract class PluginAssets : DefaultTask() {
    @get:Input abstract val ids: ListProperty<String>

    /** Ressources de chaque plugin (dans l'ordre de [ids]). */
    @get:InputFiles abstract val resources: ConfigurableFileCollection

    @get:OutputDirectory abstract val output: DirectoryProperty

    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        val dirs = resources.files.toList()
        fs.sync {
            ids.get().forEachIndexed { i, id ->
                from(dirs[i]) {
                    include("plugin.json", "web/**")
                    into("plugins/$id")
                }
            }
            into(output)
        }
    }
}

val pluginAssets = tasks.register<PluginAssets>("pluginAssets") {
    ids = bundledPlugins.keys.toList()
    bundledPlugins.values.forEach { path -> resources.from(project(path).tasks.named("processResources")) }
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(pluginAssets, PluginAssets::output) }
}

dependencies {
    implementation(project(":core"))
    bundledPlugins.values.forEach { implementation(project(it)) }
    testImplementation(kotlin("test-junit"))
}

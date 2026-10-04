package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.Audio
import fr.ftnl.apcdeck.api.PluginManifest
import fr.ftnl.apcdeck.core.device.MidiBackend
import java.io.Closeable
import java.net.InetAddress
import java.nio.file.Path

/** Ce qui change d'une plateforme à l'autre (PC, Android) ; le reste du cœur est commun. */
interface Platform {
    /** "desktop" ou "android" (l'interface adapte ce qu'elle propose). */
    val id: String

    /** Accès MIDI à l'APC. */
    val midi: MidiBackend

    /** Son donné aux plugins ([fr.ftnl.apcdeck.api.PluginContext.audio]). */
    val audio: Audio

    /** Chargement des jars du dossier plugins/. */
    val pluginLoader: PluginLoader

    /** Ouvre un dossier dans le gestionnaire de fichiers ; lève une exception si c'est impossible. */
    fun openFolder(dir: Path)

    /** Nom par défaut de l'appareil, montré aux autres pour le contrôle à distance. */
    val deviceName: String
        get() = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "APC Deck"
}

/** Charge le code d'un plugin depuis son jar (classes JVM sur PC, dex sur Android). */
interface PluginLoader {
    /** Instancie [PluginManifest.main] (sans appeler onLoad) ; [cacheDir] reçoit les copies de travail. */
    fun load(jar: Path, manifest: PluginManifest, cacheDir: Path): LoadedPlugin
}

/** Plugin chargé : son instance et ses fichiers. [close] libère le classloader et les copies. */
interface LoadedPlugin : Closeable {
    val instance: ApcPlugin

    /** Contenu du fichier [path] du jar (par exemple `web/index.html`), null s'il n'existe pas. */
    fun resource(path: String): ByteArray?
}

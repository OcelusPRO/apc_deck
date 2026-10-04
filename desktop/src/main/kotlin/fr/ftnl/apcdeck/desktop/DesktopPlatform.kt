package fr.ftnl.apcdeck.desktop

import fr.ftnl.apcdeck.api.Audio
import fr.ftnl.apcdeck.core.Platform
import fr.ftnl.apcdeck.core.PluginLoader
import fr.ftnl.apcdeck.core.device.MidiBackend
import java.awt.Desktop
import java.nio.file.Path

/** PC (Windows, macOS, Linux). */
object DesktopPlatform : Platform {
    override val id: String = "desktop"
    override val midi: MidiBackend = JavaxMidi
    override val audio: Audio = JavaSound
    override val pluginLoader: PluginLoader = JarPluginLoader

    /**
     * Ouvre le dossier dans le gestionnaire de fichiers, côté application (pas dans le navigateur).
     * Desktop.open échoue en silence depuis les threads du serveur sous Windows : on lance l'outil du système.
     */
    override fun openFolder(dir: Path) {
        val path = dir.toString()
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            "win" in os -> listOf("explorer.exe", path)
            "mac" in os -> listOf("open", path)
            else -> listOf("xdg-open", path)
        }
        try {
            ProcessBuilder(command).start()
        } catch (t: Throwable) {
            if (!Desktop.isDesktopSupported()) throw IllegalStateException("impossible d'ouvrir $path : ${t.message}")
            Desktop.getDesktop().open(dir.toFile())
        }
    }
}

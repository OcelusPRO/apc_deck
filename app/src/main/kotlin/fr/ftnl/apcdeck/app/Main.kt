package fr.ftnl.apcdeck.app

import fr.ftnl.apcdeck.core.AppBridge
import fr.ftnl.apcdeck.core.Engine
import java.awt.AWTException
import java.awt.Color
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.system.exitProcess

/**
 * Démarre le moteur et l'interface web, puis vit dans la zone de notification.
 * Options : --no-open (n'ouvre pas l'interface au démarrage).
 */
fun main(args: Array<String>) {
    val home = System.getProperty("apcdeck.home")?.let(::Path) ?: defaultHome()
    installBundledPlugins(home)
    val engine = Engine(home)
    engine.start()
    AppBridge(engine).start()

    val url = engine.web.uiUrl
    val quit = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { engine.shutdown() })

    installTrayIcon(url) { quit.countDown() }
    if ("--no-open" !in args) openUi(url)

    quit.await()
    exitProcess(0) // le hook d'arrêt ferme proprement le moteur
}

/**
 * Dossier de configuration et de données (créé au premier lancement) :
 *   Windows : %APPDATA%\.APC_Deck
 *   macOS   : ~/Library/Application Support/.APC_Deck
 *   Linux   : $XDG_CONFIG_HOME/.APC_Deck (par défaut ~/.config/.APC_Deck)
 */
private fun defaultHome(): Path {
    val os = System.getProperty("os.name").lowercase()
    val userHome = System.getProperty("user.home")
    val base = when {
        "win" in os -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::Path) ?: Path(userHome, "AppData", "Roaming")
        "mac" in os -> Path(userHome, "Library", "Application Support")
        else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::Path) ?: Path(userHome, ".config")
    }
    return base.resolve(".APC_Deck")
}

/**
 * Premier lancement d'une version packagée : copie les plugins livrés (-Dapcdeck.bundled) dans plugins/.
 * Ensuite l'utilisateur reste maître (un plugin supprimé ne revient pas).
 */
private fun installBundledPlugins(home: Path) {
    if (home.resolve("apcdeck.json").exists()) return
    val bundled = System.getProperty("apcdeck.bundled")?.let(::Path)?.takeIf { it.isDirectory() } ?: return
    val plugins = home.resolve("plugins").createDirectories()
    bundled.listDirectoryEntries("*.jar").forEach { jar ->
        runCatching { jar.copyTo(plugins.resolve(jar.name)) }
    }
}

/** Ouvre l'interface dans le navigateur par défaut. */
fun openUi(url: String) {
    val opened = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
        runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess
    if (!opened) println("Ouvre l'interface dans ton navigateur : $url")
}

private fun installTrayIcon(url: String, onQuit: () -> Unit) {
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
        println("Pas de zone de notification : Ctrl+C pour quitter. Interface : $url")
        return
    }
    val menu = PopupMenu().apply {
        add(MenuItem("Ouvrir APC Deck").apply { addActionListener { openUi(url) } })
        addSeparator()
        add(MenuItem("Quitter").apply { addActionListener { onQuit() } })
    }
    val icon = TrayIcon(trayImage(), "APC Deck", menu).apply {
        isImageAutoSize = true
        addActionListener { openUi(url) } // double-clic
    }
    try {
        SystemTray.getSystemTray().add(icon)
    } catch (e: AWTException) {
        println("Icône de notification impossible (${e.message}). Interface : $url")
    }
}

/** Petite grille 3×3 de pads colorés. */
private fun trayImage(): BufferedImage {
    val size = 32
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val colors = listOf(0xFF2A6A, 0xFF6A00, 0xFFE600, 0x22E04A, 0xD0BCFF, 0x20AAFF, 0x7A2AFF, 0xFF2AD4, 0x00F0B0)
    colors.forEachIndexed { i, rgb ->
        g.color = Color(rgb)
        g.fillRoundRect(1 + (i % 3) * 10, 1 + (i / 3) * 10, 9, 9, 3, 3)
    }
    g.dispose()
    return image
}

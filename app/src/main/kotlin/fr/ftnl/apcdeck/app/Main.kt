package fr.ftnl.apcdeck.app

import fr.ftnl.apcdeck.core.AppBridge
import fr.ftnl.apcdeck.core.Engine
import fr.ftnl.apcdeck.core.UpdateState
import fr.ftnl.apcdeck.core.UpdateStatus
import fr.ftnl.apcdeck.core.Updater
import fr.ftnl.apcdeck.core.readManifest
import java.awt.AWTException
import java.awt.Color
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.TrayIcon.MessageType
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Démarre le moteur et l'interface web, puis vit dans la zone de notification.
 * Options : --no-open (n'ouvre pas l'interface au démarrage).
 */
fun main(args: Array<String>) {
    val home = System.getProperty("apcdeck.home")?.let(::Path) ?: defaultHome()
    installBundledPlugins(home, appVersion())
    val engine = Engine(home)
    engine.start()
    val quit = CountDownLatch(1)
    val updater = Updater(appVersion(), engine.storage.cacheDir, engine.logs.logger("mises à jour"), onQuit = { quit.countDown() })
    AppBridge(engine, updater).start()

    val url = engine.web.uiUrl
    Runtime.getRuntime().addShutdownHook(Thread { engine.shutdown() })

    val tray = installTrayIcon(url, updater) { quit.countDown() }
    updater.onAvailable = { tray?.let { t -> notifyUpdate(t, it) } }
    updater.start()
    if ("--no-open" !in args) openUi(url)

    quit.await()
    exitProcess(0) // le hook d'arrêt ferme proprement le moteur
}

/** Version inscrite dans le manifeste de app.jar par le build ; null depuis les sources (gradlew run). */
private fun appVersion(): String? = object {}.javaClass.`package`?.implementationVersion

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
 * Plugins livrés avec une version packagée (-Dapcdeck.bundled) :
 *   - premier lancement : tous copiés dans plugins/ ;
 *   - première exécution d'une nouvelle version (mise à jour) : ils remplacent les plugins déjà installés de même
 *     id, pour que les mises à jour de l'application leur parviennent. Un plugin supprimé ne revient pas.
 */
private fun installBundledPlugins(home: Path, version: String?) {
    val bundled = System.getProperty("apcdeck.bundled")?.let(::Path)?.takeIf { it.isDirectory() } ?: return
    val plugins = home.resolve("plugins").createDirectories()
    val marker = home.resolve(".bundled-version") // version qui a installé les plugins livrés en dernier
    if (!home.resolve("apcdeck.json").exists()) {
        bundled.listDirectoryEntries("*.jar").forEach { jar ->
            runCatching { jar.copyTo(plugins.resolve(jar.name)) }
        }
    } else if (version != null && runCatching { marker.readText() }.getOrNull() != version) {
        val installed = plugins.listDirectoryEntries("*.jar")
            .mapNotNull { jar -> runCatching { readManifest(jar).id to jar }.getOrNull() }.toMap()
        bundled.listDirectoryEntries("*.jar").forEach { jar ->
            val target = runCatching { readManifest(jar).id }.getOrNull()?.let(installed::get) ?: return@forEach
            if (Files.mismatch(jar, target) != -1L) runCatching { jar.copyTo(target, overwrite = true) }
        }
    }
    version?.let { runCatching { marker.writeText(it) } }
}

/** Ouvre l'interface dans le navigateur par défaut. */
fun openUi(url: String) {
    val opened = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
        runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess
    if (!opened) println("Ouvre l'interface dans ton navigateur : $url")
}

private fun installTrayIcon(url: String, updater: Updater, onQuit: () -> Unit): TrayIcon? {
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
        println("Pas de zone de notification : Ctrl+C pour quitter. Interface : $url")
        return null
    }
    lateinit var icon: TrayIcon
    val menu = PopupMenu().apply {
        add(MenuItem("Ouvrir APC Deck").apply { addActionListener { openUi(url) } })
        if (updater.current != null) {
            add(MenuItem("Rechercher des mises à jour").apply {
                addActionListener { Thread({ notifyUpdate(icon, updater.check(manual = true)) }, "update-check").start() }
            })
        }
        addSeparator()
        add(MenuItem("Quitter").apply { addActionListener { onQuit() } })
    }
    icon = TrayIcon(trayImage(), "APC Deck", menu).apply {
        isImageAutoSize = true
        addActionListener { openUi(url) } // double-clic
    }
    return try {
        SystemTray.getSystemTray().add(icon)
        icon
    } catch (e: AWTException) {
        println("Icône de notification impossible (${e.message}). Interface : $url")
        null
    }
}

/** Résultat d'une recherche de mise à jour, en bulle de notification (un clic dessus ouvre l'interface). */
private fun notifyUpdate(tray: TrayIcon, u: UpdateState) = when (u.status) {
    UpdateStatus.AVAILABLE -> tray.displayMessage("Mise à jour disponible",
        "APC Deck ${u.latest} est sorti (tu as ${u.current}). Ouvre l'interface pour l'installer.", MessageType.INFO)
    UpdateStatus.UP_TO_DATE -> tray.displayMessage("APC Deck est à jour", "Version ${u.current}", MessageType.NONE)
    UpdateStatus.ERROR -> tray.displayMessage("Recherche de mise à jour impossible", u.error ?: "", MessageType.WARNING)
    else -> {}
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

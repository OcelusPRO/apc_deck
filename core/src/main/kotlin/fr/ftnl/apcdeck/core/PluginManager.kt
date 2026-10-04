package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.API_VERSION
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.ConfigField
import fr.ftnl.apcdeck.api.PluginManifest
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Format du plugin.json présent à la racine de chaque jar. */
@Serializable
private data class ManifestJson(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val apiVersion: Int,
    val description: String = "",
    val author: String = "",
    val color: Int = 3,
    val repository: String = "",
    val updateUrl: String = "",
)

private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")

fun readManifest(jar: Path): PluginManifest {
    val text = ZipFile(jar.toFile()).use { zip ->
        val entry = zip.getEntry("plugin.json") ?: error("plugin.json absent de ${jar.name}")
        zip.getInputStream(entry).bufferedReader().readText()
    }
    val m = JSON.decodeFromString<ManifestJson>(text)
    require(ID_PATTERN.matches(m.id)) { "id invalide '${m.id}' (minuscules, chiffres, - et _)" }
    require(m.apiVersion <= API_VERSION) { "${m.id} demande l'API v${m.apiVersion}, l'application fournit v$API_VERSION" }
    require(m.color in 0..127) { "couleur hors palette : ${m.color}" }
    return PluginManifest(m.id, m.name, m.version, m.main, m.apiVersion, m.description, m.author, m.color, m.repository, m.updateUrl)
}

enum class PluginStatus { ENABLED, DISABLED, ERROR }

sealed interface PluginSource {
    data class Jar(val path: Path) : PluginSource
    /** Plugin compilé avec l'application ; [resources] donne ses fichiers (`web/…`), null s'il n'en a pas. */
    data class Builtin(val factory: () -> ApcPlugin, val resources: ((String) -> ByteArray?)? = null) : PluginSource
}

/** Un plugin connu de l'application (chargé ou non). Manipulé uniquement depuis le thread principal. */
class PluginHandle(var manifest: PluginManifest, val source: PluginSource) {
    val id: String get() = manifest.id

    /** Date + taille du jar au dernier chargement : détecte un jar remplacé dans le dossier. */
    var stamp: String? = (source as? PluginSource.Jar)?.path?.let(::stampOf)
    var status: PluginStatus = PluginStatus.DISABLED
    var error: String? = null
    var instance: ApcPlugin? = null
    var context: PluginContextImpl? = null
    var config: JsonPluginConfig? = null
    private var loaded: LoadedPlugin? = null

    val isBuiltin: Boolean get() = source is PluginSource.Builtin

    /** Fichier `web/<path>` du plugin (jamais celui d'un autre plugin ou de l'application). */
    fun webResource(path: String): ByteArray? = when (source) {
        is PluginSource.Builtin -> source.resources?.invoke("web/$path")
        is PluginSource.Jar -> loaded?.resource("web/$path")
    }

    /** Le plugin a une page web (web/index.html), vu au chargement. */
    var hasWeb: Boolean = false
        private set

    /** Instancie le plugin (sans appeler onLoad). */
    fun instantiate(loader: PluginLoader, cacheDir: Path): ApcPlugin {
        val plugin = when (source) {
            is PluginSource.Builtin -> source.factory()
            is PluginSource.Jar -> loader.load(source.path, manifest, cacheDir).also { loaded = it }.instance
        }
        hasWeb = webResource("index.html") != null
        return plugin
    }

    fun release() {
        context?.scope?.cancel()
        loaded?.let { runCatching { it.close() } }
        instance = null
        context = null
        config = null
        loaded = null
        hasWeb = false
    }
}

/** Vue immuable d'un plugin, pour l'interface. */
data class PluginView(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val color: Int,
    val status: PluginStatus,
    val error: String?,
    val builtin: Boolean,
    val manager: Boolean,
    val listening: Boolean,
    val foreground: Boolean,
    /** Au premier plan et en pause. */
    val paused: Boolean,
    val fields: List<ConfigField<*>>,
    val config: Map<String, Any>,
    val dataDir: Path,
    /** Instance chargée (l'interface s'en sert pour les plugins intégrés, comme l'éditeur du pager). */
    val instance: ApcPlugin?,
    /** Adresse de l'interface web du plugin, si son jar en contient une. */
    val webUrl: String?,
    /** Dépôt git déclaré dans plugin.json (mises à jour) ; vide = aucun. */
    val repository: String = "",
    /** Adresse directe du jar déclarée dans plugin.json (mises à jour) ; vide = aucune. */
    val updateUrl: String = "",
    /** Jar installé (null pour un plugin intégré). */
    val jar: Path? = null,
)

fun stampOf(jar: Path): String? =
    runCatching { "${Files.getLastModifiedTime(jar).toMillis()}:${Files.size(jar)}" }.getOrNull()

/** Jars présents dans le dossier plugins/ (chemins absolus). */
fun listJars(storage: Storage): List<Path> =
    storage.pluginsDir.listDirectoryEntries()
        .filter { it.extension.equals("jar", ignoreCase = true) }
        .map { it.toAbsolutePath().normalize() }
        .sorted()

/** Supprime les copies de jars restées dans le cache (à appeler au démarrage uniquement). */
fun cleanCache(storage: Storage) {
    storage.cacheDir.listDirectoryEntries("*.jar").forEach { runCatching { it.deleteIfExists() } }
}

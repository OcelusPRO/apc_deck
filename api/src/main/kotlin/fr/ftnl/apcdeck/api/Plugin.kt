package fr.ftnl.apcdeck.api

import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/** Version de l'API ; un plugin déclarant une version supérieure est refusé. */
const val API_VERSION: Int = 1

/**
 * Classe de base de tout plugin. Le jar doit contenir un `plugin.json` à sa racine :
 * ```
 * { "id": "paint", "name": "Paint", "version": "1.0.0", "main": "com.exemple.PaintPlugin", "apiVersion": 1,
 *   "repository": "https://github.com/exemple/paint" }
 * ```
 * `repository` (facultatif) : dépôt GitHub dont les releases publient le plugin. L'application compare la
 * version de la dernière release (tag `v1.2.0` ou `1.2.0`) à celle du plugin et propose la mise à jour ; la
 * release doit contenir le jar sous le nom `<id>.jar` (ici `paint.jar`).
 * Tous les hooks sont appelés sur le thread principal de l'application : pas de synchronisation à prévoir.
 * Cycle de vie : onLoad -> (onActivate <-> onDeactivate)* -> onUnload.
 */
abstract class ApcPlugin {
    private var _ctx: PluginContext? = null

    /** Disponible à partir de [onLoad]. */
    val ctx: PluginContext get() = _ctx ?: error("contexte non disponible avant onLoad")

    /** Appelé par l'application, une seule fois, avant [onLoad]. */
    fun attach(context: PluginContext) {
        check(_ctx == null) { "plugin déjà attaché" }
        _ctx = context
    }

    /** Schéma de configuration affiché dans l'interface. */
    open val configSpec: ConfigSpec get() = ConfigSpec.EMPTY

    open fun onLoad() {}
    open fun onUnload() {}

    /** Le plugin passe au premier plan : il possède l'écran LED, à redessiner entièrement. */
    open fun onActivate() {}
    open fun onDeactivate() {}
    open fun onPause() {}

    /** Par défaut, redessine via [onActivate] (l'écran LED a été effacé pendant la pause). */
    open fun onResume() {
        onActivate()
    }

    /**
     * Décidé par le plugin lui-même : faux (défaut) = il ne reçoit que ce qui lui est destiné, c'est-à-dire
     * les événements quand il est au premier plan ; vrai = il reçoit aussi tous les événements quand un autre
     * plugin a la main ([onBackgroundEvent]) et continue de recevoir [onTick]. Relu à chaque événement :
     * peut changer en cours de route.
     */
    open val listensInBackground: Boolean get() = false

    /** Événement reçu au premier plan (hors touches system). */
    open fun onEvent(event: ApcEvent) {}

    /** Événement reçu quand un autre plugin a la main, seulement si [listensInBackground] est vrai. */
    open fun onBackgroundEvent(event: ApcEvent) {}

    /** Appelé à intervalle régulier quand le plugin tourne (premier plan, ou arrière-plan si [listensInBackground]). */
    open fun onTick(deltaMillis: Long) {}

    /** Une valeur de configuration a été modifiée depuis l'interface. */
    open fun onConfigChanged() {}

    /**
     * Appel depuis la page web du plugin : `await apcdeck.call(action, data)`.
     * [body] est le JSON envoyé par la page ; la valeur renvoyée (JSON, ou null) est la réponse.
     */
    open fun onWebCall(action: String, body: String): String? = null
}

/** Plugin gestionnaire (le pager par défaut) : seul destinataire des touches system. */
abstract class ManagerPlugin : ApcPlugin() {
    /** [button] vaut PLAY, REC ou SUSTAIN ; SHIFT est exposé via [PluginContext.shift]. */
    abstract fun onSystemKey(button: Button, pressed: Boolean)

    /** Un plugin a été installé, retiré, activé, désactivé, ou son écoute en arrière-plan a changé. */
    open fun onPluginsChanged() {}

    /** Le plugin [id] a été désinstallé (son jar est supprimé) : oublier ce qui le concerne. */
    open fun onPluginRemoved(id: String) {}
}

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val apiVersion: Int,
    val description: String = "",
    val author: String = "",
    /** Couleur du plugin dans le menu (index de palette). */
    val color: Int = PadColor.WHITE.index,
    /** Dépôt GitHub (`https://github.com/<owner>/<repo>` ou `<owner>/<repo>`) : mises à jour ; vide = aucune. */
    val repository: String = "",
)

interface PluginContext {
    val manifest: PluginManifest
    val id: String get() = manifest.id

    /** L'écran LED réel si ce plugin est au premier plan (et pas en pause), sinon un écran factice. */
    val leds: Leds
    val isForeground: Boolean
    val shift: Boolean

    val log: PluginLogger
    val config: PluginConfig
    val data: DataStore

    /** Portée liée au plugin (annulée au déchargement), exécutée sur le thread principal. */
    val scope: CoroutineScope

    /** Communication avec la page web du plugin (dossier `web/` du jar). */
    val web: WebBridge

    /** Pilotage de l'application (utilisé surtout par le gestionnaire). */
    val host: Host
}

interface PluginLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

/** Stockage clé/valeur persistant propre au plugin, plus un dossier libre pour ses fichiers. */
interface DataStore {
    val directory: Path
    val keys: Set<String>

    fun getString(key: String): String?

    /** null supprime la clé. */
    fun putString(key: String, value: String?)

    fun getInt(key: String, default: Int): Int = getString(key)?.toIntOrNull() ?: default
    fun putInt(key: String, value: Int): Unit = putString(key, value.toString())
    fun getBoolean(key: String, default: Boolean): Boolean = getString(key)?.toBooleanStrictOrNull() ?: default
    fun putBoolean(key: String, value: Boolean): Unit = putString(key, value.toString())
}

data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val color: PadColor,
    /** Le plugin a déclaré écouter en arrière-plan ([ApcPlugin.listensInBackground]). */
    val listening: Boolean,
    val foreground: Boolean,
)

interface Host {
    val foregroundId: String?

    /** Dernier plugin (hors gestionnaire) qui avait la main. */
    val previousId: String?
    val isPaused: Boolean

    /** Plugins chargés, hors gestionnaire, dans l'ordre du menu. */
    fun plugins(): List<PluginInfo>
    fun activate(id: String)
    fun goHome()

    /** Menu <-> dernier plugin. */
    fun toggleHome()
    fun togglePause()
}

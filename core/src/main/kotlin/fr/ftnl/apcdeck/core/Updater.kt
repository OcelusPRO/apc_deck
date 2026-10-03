package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.PluginLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

enum class UpdateStatus { DISABLED, IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

data class UpdateState(
    val current: String?,
    val status: UpdateStatus,
    val latest: String? = null,
    /** Page de la release sur GitHub. */
    val pageUrl: String? = null,
    /** Installeur de l'OS courant (null : la release n'en contient pas, il faut passer par la page). */
    val assetName: String? = null,
    val notes: String? = null,
    /** 0..1 pendant le téléchargement. */
    val progress: Double? = null,
    val error: String? = null,
    val checkedAt: Long? = null,
)

/**
 * Recherche des mises à jour dans les releases GitHub du projet : au démarrage puis toutes les [interval].
 * Une version plus récente est signalée (interface + [onAvailable]) ; l'installation se fait sur demande :
 * téléchargement de l'installeur de l'OS, lancement, puis fermeture de l'application ([onQuit]) pour qu'il
 * puisse remplacer ses fichiers.
 *
 * [current] null (lancement depuis les sources, sans version) : recherche désactivée.
 */
class Updater(
    val current: String?,
    private val cacheDir: Path,
    private val log: PluginLogger,
    private val onQuit: () -> Unit,
    private val repo: String = REPO,
    private val interval: Duration = 6.hours,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private var assetUrl: String? = null

    private val _state = MutableStateFlow(UpdateState(current, if (current == null) UpdateStatus.DISABLED else UpdateStatus.IDLE))
    val state: StateFlow<UpdateState> = _state

    /** Appelé (thread quelconque) quand une version plus récente que la dernière signalée est trouvée. */
    var onAvailable: (UpdateState) -> Unit = {}
    private var announced: String? = null

    fun start() {
        if (current == null) return log.info("version de développement : recherche de mises à jour désactivée")
        scope.launch {
            delay(10.seconds) // laisse l'application démarrer
            while (true) {
                check()
                delay(interval)
            }
        }
    }

    /**
     * Recherche maintenant et renvoie l'état obtenu (inchangé pendant un téléchargement). [manual] : demandé par
     * l'utilisateur, qui verra le résultat ; [onAvailable] n'est alors pas appelé.
     */
    fun check(manual: Boolean = false): UpdateState {
        if (current == null || !busy.compareAndSet(false, true)) return _state.value
        try {
            _state.update { it.copy(status = UpdateStatus.CHECKING, error = null) }
            val release = Json.parseToJsonElement(httpGet("https://api.github.com/repos/$repo/releases/latest")).jsonObject
            val tag = release.string("tag_name") ?: error("release sans tag")
            val latest = tag.removePrefix("v")
            val asset = release["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { a ->
                a.string("name")?.lowercase()?.endsWith(installerExtension()) == true
            }
            assetUrl = asset?.string("browser_download_url")
            val newer = compareVersions(latest, current) > 0
            val next = UpdateState(
                current = current,
                status = if (newer) UpdateStatus.AVAILABLE else UpdateStatus.UP_TO_DATE,
                latest = latest,
                pageUrl = release.string("html_url"),
                assetName = asset?.string("name"),
                notes = release.string("body"),
                checkedAt = System.currentTimeMillis(),
            )
            _state.value = next
            if (newer && announced != latest) {
                announced = latest
                log.info("mise à jour disponible : $current -> $latest")
                if (!manual) onAvailable(next)
            }
        } catch (_: NoRelease) {
            // Pas encore de release publiée : rien de plus récent.
            _state.value = UpdateState(current, UpdateStatus.UP_TO_DATE, checkedAt = System.currentTimeMillis())
        } catch (t: Throwable) {
            log.warn("recherche de mise à jour impossible : ${t.message ?: t::class.simpleName}")
            _state.update { it.copy(status = UpdateStatus.ERROR, error = t.message ?: t::class.simpleName, checkedAt = System.currentTimeMillis()) }
        } finally {
            busy.set(false)
        }
        return _state.value
    }

    /** Télécharge l'installeur, le lance et ferme l'application. Ne bloque pas. */
    fun install() {
        val url = assetUrl ?: error("aucun installeur pour ce système dans la release : passe par la page GitHub")
        if (_state.value.status != UpdateStatus.AVAILABLE) error("aucune mise à jour à installer")
        if (!busy.compareAndSet(false, true)) error("opération déjà en cours")
        scope.launch {
            try {
                val name = _state.value.assetName ?: "APCDeck-update${installerExtension()}"
                val file = download(url, cacheDir.resolve("update").also(Files::createDirectories).resolve(name))
                _state.update { it.copy(status = UpdateStatus.INSTALLING, progress = 1.0) }
                log.info("lancement de l'installeur $name")
                launchInstaller(file)
                delay(1.seconds)
                onQuit()
            } catch (t: Throwable) {
                log.error("mise à jour impossible", t)
                _state.update { it.copy(status = UpdateStatus.AVAILABLE, progress = null, error = t.message ?: t::class.simpleName) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun download(url: String, target: Path): Path {
        _state.update { it.copy(status = UpdateStatus.DOWNLOADING, progress = 0.0, error = null) }
        val connection = open(url)
        val total = connection.contentLengthLong
        val partial = target.resolveSibling("${target.fileName}.part")
        connection.inputStream.use { input ->
            Files.newOutputStream(partial).use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0 && done - lastReport > total / 100) {
                        lastReport = done
                        _state.update { it.copy(progress = done.toDouble() / total) }
                    }
                }
            }
        }
        return Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Windows : l'installeur .exe (il met à jour l'installation existante) ; ailleurs : l'outil du système. */
    private fun launchInstaller(file: Path) {
        val path = file.toAbsolutePath().toString()
        val command = when (Os.current) {
            Os.WINDOWS -> listOf(path)
            Os.MACOS -> listOf("open", path)
            Os.LINUX -> listOf("xdg-open", path)
        }
        ProcessBuilder(command).inheritIO().start()
    }

    private fun installerExtension() = when (Os.current) {
        Os.WINDOWS -> ".exe"
        Os.MACOS -> ".dmg"
        Os.LINUX -> ".deb"
    }

    private fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true // les fichiers des releases sont servis via une redirection
            setRequestProperty("User-Agent", "APCDeck/${current ?: "dev"}")
            setRequestProperty("Accept", "application/vnd.github+json")
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                if (code == 404) throw NoRelease()
                error("GitHub a répondu $code")
            }
        }

    private fun httpGet(url: String): String = open(url).let { c -> c.inputStream.use { it.readBytes().decodeToString() } }

    private class NoRelease : Exception()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    enum class Os {
        WINDOWS, MACOS, LINUX;

        companion object {
            val current: Os = System.getProperty("os.name").lowercase().let {
                when {
                    "win" in it -> WINDOWS
                    "mac" in it -> MACOS
                    else -> LINUX
                }
            }
        }
    }

    companion object {
        const val REPO = "OcelusPRO/apc_deck"

        /** Compare "1.10.0" et "1.9.2" nombre par nombre (un suffixe comme "-beta" est ignoré). */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

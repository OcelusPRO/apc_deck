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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

enum class UpdateStatus { DISABLED, IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

/**
 * Plugin dont la source de mise à jour ([fr.ftnl.apcdeck.api.PluginManifest.repository] ou
 * [fr.ftnl.apcdeck.api.PluginManifest.updateUrl]) publie une version plus récente.
 */
data class PluginUpdate(
    val id: String,
    val name: String,
    val current: String,
    val latest: String,
    val pageUrl: String?,
    /** Ce qui distingue cette mise à jour (version, ou empreinte du jar pour une URL directe) : signalée une fois. */
    val revision: String = latest,
    val installing: Boolean = false,
    val error: String? = null,
)

data class UpdateState(
    val current: String?,
    /** État de l'application elle-même (les plugins sont dans [plugins]). */
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
    val plugins: List<PluginUpdate> = emptyList(),
)

/**
 * Recherche des mises à jour : releases de l'application ([repo]) et des plugins qui déclarent une source dans leur
 * plugin.json, soit un dépôt git (GitHub, GitLab, Gitea/Forgejo ; la release doit contenir `<id>.jar`), soit l'URL
 * directe de leur jar (suivie par requêtes conditionnelles et comparaison d'empreinte). Au démarrage puis toutes
 * les [interval] ; les nouveautés sont signalées (interface + [onAvailable]) et s'installent sur demande :
 *   - plugin : le jar est téléchargé, vérifié, puis installé à chaud ([installJar]) ;
 *   - application : téléchargement de l'installeur de l'OS, lancement, puis fermeture ([onQuit]) pour qu'il
 *     puisse remplacer ses fichiers. Sous Windows, une fois l'installeur terminé, une fenêtre propose de relancer
 *     l'application (option [AFTER_UPDATE] : l'interface ne se rouvre que si aucun onglet ne l'affiche déjà).
 *
 * [current] null (lancement depuis les sources, sans version) : recherche désactivée.
 */
class Updater(
    val current: String?,
    private val cacheDir: Path,
    private val log: PluginLogger,
    private val onQuit: () -> Unit,
    /** Plugins installés (seuls ceux qui ne sont pas intégrés et déclarent un dépôt sont vérifiés). */
    private val plugins: () -> List<PluginView> = { emptyList() },
    /** Installe (ou remplace) un plugin à partir d'un jar téléchargé ; le fichier peut être supprimé ensuite. */
    private val installJar: (Path) -> Unit = {},
    private val repo: String = REPO,
    private val interval: Duration = 6.hours,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val client = ReleaseClient("APCDeck/${current ?: "dev"}")
    private var assetUrl: String? = null
    private val pluginSources = ConcurrentHashMap<String, UpdateSource>()

    private val _state = MutableStateFlow(UpdateState(current, if (current == null) UpdateStatus.DISABLED else UpdateStatus.IDLE))
    val state: StateFlow<UpdateState> = _state

    /** Appelé (thread quelconque) quand une version plus récente que celles déjà signalées est trouvée. */
    var onAvailable: (UpdateState) -> Unit = {}
    private val announced = mutableSetOf<String>()

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
        val current = current ?: return _state.value
        if (!busy.compareAndSet(false, true)) return _state.value
        try {
            _state.update { it.copy(status = UpdateStatus.CHECKING, error = null) }
            val releases = HashMap<String, Release?>() // un dépôt partagé par plusieurs plugins n'est lu qu'une fois
            val next = checkApp(current, releases).copy(plugins = checkPlugins(releases))
            _state.value = next
            val found = buildList {
                if (next.status == UpdateStatus.AVAILABLE) add("app@${next.latest}")
                next.plugins.forEach { add("${it.id}@${it.revision}") }
            }.filter { announced.add(it) }
            if (found.isNotEmpty()) {
                log.info("mises à jour disponibles : ${found.joinToString()}")
                if (!manual) onAvailable(next)
            }
        } finally {
            busy.set(false)
        }
        return _state.value
    }

    private fun checkApp(current: String, releases: MutableMap<String, Release?>): UpdateState = try {
        val ref = RepoRef.parse(repo) ?: error("dépôt de l'application invalide : $repo")
        val release = client.latestRelease(ref).also { releases[ref.key] = it }
        if (release == null) {
            UpdateState(current, UpdateStatus.UP_TO_DATE, checkedAt = System.currentTimeMillis()) // aucune release publiée
        } else {
            val asset = release.asset { it.endsWith(installerExtension()) }
            assetUrl = asset?.url
            UpdateState(
                current = current,
                status = if (compareVersions(release.version, current) > 0) UpdateStatus.AVAILABLE else UpdateStatus.UP_TO_DATE,
                latest = release.version,
                pageUrl = release.pageUrl,
                assetName = asset?.name,
                notes = release.notes,
                checkedAt = System.currentTimeMillis(),
            )
        }
    } catch (t: Throwable) {
        log.warn("recherche de mise à jour impossible : ${t.message ?: t::class.simpleName}")
        UpdateState(current, UpdateStatus.ERROR, error = t.message ?: t::class.simpleName, checkedAt = System.currentTimeMillis())
    }

    private fun checkPlugins(releases: MutableMap<String, Release?>): List<PluginUpdate> {
        val installing = _state.value.plugins.filter { it.installing }.associateBy { it.id }
        return plugins().filter { !it.builtin && (it.updateUrl.isNotBlank() || it.repository.isNotBlank()) }.mapNotNull { p ->
            installing[p.id]?.let { return@mapNotNull it }
            try {
                if (p.updateUrl.isNotBlank()) checkDirect(p) else checkRepository(p, releases)
            } catch (t: Throwable) {
                log.warn("${p.id} : recherche de mise à jour impossible : ${t.message ?: t::class.simpleName}")
                null
            }
        }
    }

    /** Plugin publié dans les releases d'un dépôt git : version de la dernière release + fichier `<id>.jar`. */
    private fun checkRepository(p: PluginView, releases: MutableMap<String, Release?>): PluginUpdate? {
        val ref = RepoRef.parse(p.repository) ?: error("adresse de dépôt non reconnue « ${p.repository} »")
        val release = releases.getOrPut(ref.key) { client.latestRelease(ref) } ?: return null
        if (compareVersions(release.version, p.version) <= 0) return null
        val asset = release.pluginJar(p.id) ?: error("la release ${release.version} de ${ref.host}/${ref.path} ne contient pas ${p.id}.jar")
        pluginSources[p.id] = UpdateSource.Remote(asset.url)
        return PluginUpdate(p.id, p.name, p.version, release.version, release.pageUrl, revision = release.version)
    }

    /** Dernier téléchargement d'une URL directe : validateurs HTTP, fichier, empreinte et version lue dans le jar. */
    private class DirectJar(val validators: ReleaseClient.Validators, val file: Path, val sha: String, val version: String)
    private val directJars = ConcurrentHashMap<String, DirectJar>()

    /**
     * Plugin suivi par URL directe : le jar n'est retéléchargé que s'il a changé (ETag / Last-Modified), et la
     * mise à jour est proposée si son contenu diffère du jar installé, sauf s'il s'agit d'une version plus ancienne.
     */
    private fun checkDirect(p: PluginView): PluginUpdate? {
        val url = p.updateUrl.trim()
        val file = cacheDir.resolve("update").resolve("${p.id}-url.jar")
        val known = directJars[url]?.takeIf { Files.exists(it.file) }
        val jar = client.downloadIfChanged(url, file, known?.validators)?.let { validators ->
            val manifest = readManifest(file)
            require(manifest.id == p.id) { "$url contient le plugin « ${manifest.id} » au lieu de « ${p.id} »" }
            DirectJar(validators, file, ReleaseClient.sha256(file), manifest.version).also { directJars[url] = it }
        } ?: known ?: return null
        if (p.jar != null && Files.exists(p.jar) && ReleaseClient.sha256(p.jar) == jar.sha) return null
        val order = compareVersions(jar.version, p.version)
        if (order < 0) return null
        pluginSources[p.id] = UpdateSource.Local(jar.file)
        val label = if (order == 0) "${jar.version} (nouveau fichier)" else jar.version
        return PluginUpdate(p.id, p.name, p.version, label, pageUrl = null, revision = jar.sha)
    }

    /** D'où vient le jar d'une mise à jour de plugin. */
    private sealed interface UpdateSource {
        data class Remote(val url: String) : UpdateSource
        data class Local(val file: Path) : UpdateSource
    }

    /** Télécharge (si besoin) et installe la mise à jour du plugin [id] (sans fermer l'application). Ne bloque pas. */
    fun installPlugin(id: String) {
        val update = _state.value.plugins.firstOrNull { it.id == id } ?: error("aucune mise à jour pour $id")
        val source = pluginSources[id] ?: error("aucune mise à jour pour $id")
        if (update.installing) return
        setPlugin(id) { it.copy(installing = true, error = null) }
        scope.launch {
            try {
                // Copie dédiée : l'installation la supprime, le fichier suivi par URL directe doit rester.
                val target = cacheDir.resolve("update").resolve("$id-${System.nanoTime()}.jar")
                val jar = when (source) {
                    is UpdateSource.Remote -> client.download(source.url, target)
                    is UpdateSource.Local -> Files.copy(source.file, target)
                }
                val manifest = readManifest(jar)
                require(manifest.id == id) { "le jar téléchargé est le plugin « ${manifest.id} » au lieu de « $id »" }
                installJar(jar)
                log.info("${update.name} : ${update.current} -> ${manifest.version}")
                _state.update { s -> s.copy(plugins = s.plugins.filter { it.id != id }) }
            } catch (t: Throwable) {
                log.error("mise à jour de $id impossible", t)
                setPlugin(id) { it.copy(installing = false, error = t.message ?: t::class.simpleName) }
            }
        }
    }

    /** Toutes les mises à jour de plugins en attente. */
    fun installPlugins() = _state.value.plugins.filter { !it.installing }.forEach { installPlugin(it.id) }

    private fun setPlugin(id: String, change: (PluginUpdate) -> PluginUpdate) =
        _state.update { s -> s.copy(plugins = s.plugins.map { if (it.id == id) change(it) else it }) }

    /** Télécharge l'installeur, le lance et ferme l'application. Ne bloque pas. */
    fun install() {
        val url = assetUrl ?: error("aucun installeur pour ce système dans la release : passe par la page GitHub")
        if (_state.value.status != UpdateStatus.AVAILABLE) error("aucune mise à jour à installer")
        if (!busy.compareAndSet(false, true)) error("opération déjà en cours")
        scope.launch {
            try {
                val name = _state.value.assetName ?: "APCDeck-update${installerExtension()}"
                _state.update { it.copy(status = UpdateStatus.DOWNLOADING, progress = 0.0, error = null) }
                val file = client.download(url, cacheDir.resolve("update").also(Files::createDirectories).resolve(name)) { p ->
                    _state.update { it.copy(progress = p) }
                }
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

    /**
     * Windows : l'installeur .exe (il met à jour l'installation existante), suivi par un petit script qui attend
     * sa fin puis propose de relancer l'application ; ailleurs : l'outil du système (relance à la main).
     */
    private fun launchInstaller(file: Path) {
        val path = file.toAbsolutePath().toString()
        val app = ProcessHandle.current().info().command().orElse(null)
            ?.takeIf { it.endsWith(".exe", ignoreCase = true) && !it.endsWith("java.exe", ignoreCase = true) }
        val command = when (Os.current) {
            Os.WINDOWS if app != null -> {
                // BOM : Windows PowerShell lit sinon le script comme de l'ANSI (accents abîmés).
                val script = file.resolveSibling("relaunch.ps1")
                Files.write(script, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + RELAUNCH_SCRIPT.toByteArray())
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", script.toString(), "-Installer", path, "-App", app)
            }
            Os.WINDOWS -> listOf(path)
            Os.MACOS -> listOf("open", path)
            Os.LINUX -> listOf("xdg-open", path)
        }
        // Le processus lancé survit à la fermeture de l'application.
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    private fun installerExtension() = when (Os.current) {
        Os.WINDOWS -> ".exe"
        Os.MACOS -> ".dmg"
        Os.LINUX -> ".deb"
    }

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

        /** Option de lancement après une mise à jour (voir [Updater]). */
        const val AFTER_UPDATE = "--after-update"

        /** Attend l'installeur puis propose la relance (fenêtre au premier plan). */
        private val RELAUNCH_SCRIPT = $$"""
            param([string]$Installer, [string]$App)
            $process = Start-Process -FilePath $Installer -PassThru -Wait
            Add-Type -AssemblyName System.Windows.Forms
            $owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true }
            $message = if ($process.ExitCode -eq 0) { "La mise à jour d'APC Deck est installée. Relancer l'application maintenant ?" }
                       else { "La mise à jour n'a pas été terminée. Relancer APC Deck ?" }
            $answer = [System.Windows.Forms.MessageBox]::Show($owner, $message, "APC Deck", "YesNo", "Question")
            if ($answer -eq "Yes") { Start-Process -FilePath $App -ArgumentList "$$AFTER_UPDATE" }
        """.trimIndent()

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

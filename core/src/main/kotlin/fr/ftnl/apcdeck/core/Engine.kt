package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.BindingField
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.InputBinding
import fr.ftnl.apcdeck.api.InputKind
import fr.ftnl.apcdeck.api.PadEvent
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.ConfigField
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.Host
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.KnobEvent
import fr.ftnl.apcdeck.api.Leds
import fr.ftnl.apcdeck.api.ManagerPlugin
import fr.ftnl.apcdeck.api.PadColor
import fr.ftnl.apcdeck.api.PluginContext
import fr.ftnl.apcdeck.api.PluginInfo
import fr.ftnl.apcdeck.api.PluginLogger
import fr.ftnl.apcdeck.api.PluginManifest
import fr.ftnl.apcdeck.api.WebBridge
import java.util.concurrent.Callable
import java.security.SecureRandom
import java.util.HexFormat
import fr.ftnl.apcdeck.core.builtin.BUILTIN_PLUGINS
import fr.ftnl.apcdeck.core.device.ApcDevice
import fr.ftnl.apcdeck.core.device.DeviceMode
import fr.ftnl.apcdeck.core.device.KnobMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolute
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.name

data class DeviceStatus(val connected: Boolean, val detail: String)

data class LearnState(val pluginId: String, val fieldKey: String)

/** Ce qui est physiquement enfoncé / la position des potars (pads indexés y * 8 + x). */
data class InputState(
    val pads: Set<Int> = emptySet(),
    val buttons: Set<Button> = emptySet(),
    val notes: Set<Int> = emptySet(),
    val knobs: List<Int> = List(8) { 64 },
) {
    fun after(event: ApcEvent): InputState = when (event) {
        is PadEvent -> copy(pads = if (event.pressed) pads + event.index else pads - event.index)
        is ButtonEvent -> copy(buttons = if (event.pressed) buttons + event.button else buttons - event.button)
        is KeyEvent -> copy(notes = if (event.pressed) notes + event.note else notes - event.note)
        is KnobEvent -> copy(knobs = knobs.toMutableList().also { if (event.index in it.indices) it[event.index] = event.value })
    }
}

class PluginContextImpl(
    private val engine: Engine,
    private val handle: PluginHandle,
    override val config: JsonPluginConfig,
    override val data: JsonDataStore,
    override val log: PluginLogger,
) : PluginContext {
    override val manifest: PluginManifest get() = handle.manifest
    override val leds: Leds get() = engine.ledsFor(handle.id)
    override val isForeground: Boolean get() = engine.foregroundId == handle.id
    override val shift: Boolean get() = engine.shift
    override val host: Host get() = engine.host
    override val web: WebBridge = object : WebBridge {
        override val available: Boolean get() = handle.hasWeb
        override fun emit(event: String, json: String) = engine.web.emit(handle.id, event, json)
    }
    override val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + engine.dispatcher + CoroutineExceptionHandler { _, t -> log.error("coroutine en échec", t) },
    )
}

/**
 * Cœur de l'application. Tout l'état est confiné au thread "apc-main" : les méthodes publiques
 * (appelées par l'interface) postent leur travail sur ce thread ; les plugins y sont toujours appelés.
 */
class Engine(home: Path, private val builtins: List<Pair<PluginManifest, () -> ApcPlugin>> = BUILTIN_PLUGINS) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "apc-main").apply { isDaemon = true } }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    val logs = LogBuffer()
    private val log = logs.logger("core")
    val storage = Storage(home)
    private var settings: Settings = runCatching { storage.loadSettings() }
        .getOrElse { log.error("apcdeck.json illisible, valeurs par défaut", it); Settings() }

    val surface = Surface()
    private val device = ApcDevice { event -> post { dispatch(event) } }
    private val handles = LinkedHashMap<String, PluginHandle>()
    private var lastConnectError: String? = null
    private var lastTick = System.nanoTime()

    var foregroundId: String? = null
        private set
    var previousId: String? = null
        private set
    var paused: Boolean = false
        private set
    var shift: Boolean = false
        private set

    private val _plugins = MutableStateFlow<List<PluginView>>(emptyList())
    val plugins: StateFlow<List<PluginView>> = _plugins
    private val _device = MutableStateFlow(DeviceStatus(false, "non connecté"))
    val deviceStatus: StateFlow<DeviceStatus> = _device
    private val _settings = MutableStateFlow(settings)
    val settingsState: StateFlow<Settings> = _settings

    /** Assignation en attente d'un appui (null sinon). */
    private val _learning = MutableStateFlow<LearnState?>(null)
    val learningState: StateFlow<LearnState?> = _learning

    /** Entrées physiques en cours (pads, boutons, touches enfoncés ; position des potars), pour l'interface. */
    private val _input = MutableStateFlow(InputState())
    val input: StateFlow<InputState> = _input

    val host: Host = HostImpl()
    private val managerId: String get() = settings.manager

    /** Serveur local : interface de l'application et interfaces web des plugins. */
    val web = WebServer(
        token = settings.uiToken.ifBlank {
            HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes)).also { token ->
                updateSettings { it.copy(uiToken = token) }
            }
        },
        preferredPort = settings.uiPort,
        // L'état des plugins est confiné au thread principal : on y résout aussi les fichiers.
        resource = { id, path -> executor.submit(Callable { handles[id]?.webResource(path) }).get(5, TimeUnit.SECONDS) },
        call = ::webCall,
        log = { message, t -> log.error(message, t) },
    )

    /** Appel depuis une page web : exécuté sur le thread principal, réponse attendue 5 s au plus. */
    private fun webCall(id: String, action: String, body: String): Result<String?> = runCatching {
        executor.submit(Callable {
            val plugin = handles[id]?.instance ?: error("plugin '$id' non chargé")
            try {
                plugin.onWebCall(action, body)
            } finally {
                flush()
            }
        }).get(5, TimeUnit.SECONDS)
    }.onFailure { logs.add(LogLevel.ERROR, id, "appel web '$action' en échec", it.cause ?: it) }

    // --- API pour l'interface (thread-safe) -----------------------------------

    fun start() {
        post {
            builtins.forEach { (manifest, factory) -> register(PluginHandle(manifest, PluginSource.Builtin(factory))) }
            cleanCache(storage)
            listJars(storage).forEach { jar ->
                runCatching { register(PluginHandle(readManifest(jar), PluginSource.Jar(jar))) }
                    .onFailure { log.error("${jar.name} ignoré", it) }
            }
            ordered().filter { settingsOf(it.id).enabled || it.id == managerId }.forEach(::enable)
            if (handles[managerId]?.instance !is ManagerPlugin) log.error("gestionnaire '$managerId' absent ou invalide")
            activateNow(managerId)
            refreshManager()
            publish()
        }
        executor.scheduleAtFixedRate({ guarded { tick() }; flush() }, 0, 1000L / settings.fps.coerceIn(1, 120), TimeUnit.MILLISECONDS)
        executor.scheduleWithFixedDelay({ if (!device.isOpen) guarded { connect() } }, 0, 3, TimeUnit.SECONDS)
        runCatching { watchPluginsDir() }.onFailure { log.error("surveillance du dossier plugins impossible", it) }
        runCatching { web.start() }
            .onSuccess { log.info("interface : ${web.uiUrl}") }
            .onFailure { log.error("serveur web impossible à démarrer", it) }
    }

    /** Relit le dossier plugins/ : nouveaux jars chargés, jars remplacés rechargés, jars supprimés retirés. */
    fun rescan() = post { rescanNow() }

    fun shutdown() {
        runCatching {
            executor.submit {
                handles.values.forEach { h -> call(h) { it.onUnload() }; h.release() }
                surface.clear()
                runCatching { surface.flush(device) }
                device.close()
            }.get(3, TimeUnit.SECONDS)
        }
        executor.shutdownNow()
        runCatching { web.stop() }
    }

    fun reconnect() = post { device.close(); lastConnectError = null; connect() }

    fun setDeviceMode(mode: DeviceMode, knobs: KnobMode) = post {
        updateSettings { it.copy(mode = mode, knobs = knobs) }
        device.close()
        connect()
    }

    /** Simule un événement (pads cliqués dans l'interface). */
    fun simulate(event: ApcEvent) = post { dispatch(event) }

    fun install(jar: Path, deleteAfter: Boolean = false) = post {
        try {
            installNow(jar)
        } finally {
            if (deleteAfter) runCatching { jar.deleteIfExists() }
        }
    }

    /** Modifie un champ de configuration désigné par sa clé ; [decode] convertit la valeur reçue (null = invalide). */
    fun setConfigByKey(id: String, key: String, decode: (ConfigField<*>) -> Any?) = post {
        val h = handles[id] ?: return@post
        val field = h.config?.spec?.fields?.firstOrNull { it.key == key } ?: return@post
        val value = decode(field) ?: return@post log.warn("$id : valeur invalide pour « ${field.label} »")
        h.config?.setUnchecked(field, value)
        call(h) { it.onConfigChanged() }
        publish()
    }
    fun uninstall(id: String) = post { uninstallNow(id) }
    fun reload(id: String) = post { reloadNow(id) }
    fun setEnabled(id: String, enabled: Boolean) = post { setEnabledNow(id, enabled) }
    fun activate(id: String) = post { activateNow(id) }

    fun setConfig(id: String, field: ConfigField<*>, value: Any) = post {
        val h = handles[id] ?: return@post
        h.config?.setUnchecked(field, value) ?: return@post
        call(h) { it.onConfigChanged() }
        publish()
    }

    // --- boucle --------------------------------------------------------------

    private fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute { guarded(block); flush() }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log.error("erreur interne", t)
        }
    }

    private fun call(h: PluginHandle, block: (ApcPlugin) -> Unit) {
        val plugin = h.instance ?: return
        try {
            block(plugin)
        } catch (t: Throwable) {
            logs.add(LogLevel.ERROR, h.id, "exception dans le plugin", t)
        }
    }

    private fun flush() {
        try {
            surface.flush(device.takeIf { it.isOpen })
        } catch (t: Throwable) {
            device.close()
            _device.value = DeviceStatus(false, "connexion perdue : ${t.message}")
            log.warn("APC déconnecté (${t.message})")
        }
    }

    private fun connect() {
        try {
            device.knobMode = settings.knobs
            val detail = device.open(settings.mode)
            surface.invalidate()
            lastConnectError = null
            _device.value = DeviceStatus(true, detail)
            log.info("APC connecté : $detail")
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "erreur"
            _device.value = DeviceStatus(false, message)
            if (message != lastConnectError) log.warn("APC indisponible : $message")
            lastConnectError = message
        }
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = (now - lastTick) / 1_000_000
        lastTick = now
        running().forEach { h -> call(h) { it.onTick(dt) } }
        checkListeningChanged()
    }

    private fun running(): List<PluginHandle> = handles.values.filter {
        it.instance != null && ((it.id == foregroundId && !paused) || (it.id != foregroundId && isListening(it.id)))
    }

    private fun dispatch(event: ApcEvent) {
        _input.value = _input.value.after(event)
        if (learning != null && captureBinding(event)) return
        swallowRelease?.let { pending ->
            // Relâchement de l'entrée qui vient d'être assignée : personne ne doit le recevoir.
            if (isReleaseOf(event, pending)) {
                swallowRelease = null
                return
            }
        }
        if (event is ButtonEvent && event.button.group == Button.Group.SYSTEM) {
            if (event.button == Button.SHIFT) shift = event.pressed
            else handles[managerId]?.let { h -> call(h) { (it as? ManagerPlugin)?.onSystemKey(event.button, event.pressed) } }
            return
        }
        foregroundId?.takeIf { !paused }?.let { id -> handles[id]?.let { h -> call(h) { it.onEvent(event) } } }
        handles.values
            .filter { it.id != foregroundId && it.instance != null && isListening(it.id) }
            .forEach { h -> call(h) { it.onBackgroundEvent(event) } }
    }

    fun ledsFor(id: String): Leds = if (id == foregroundId && !paused) surface else NullLeds

    // --- assignation des entrées (mode écoute) -----------------------------------

    private var learning: Pair<PluginHandle, BindingField>? = null
    private var swallowRelease: InputBinding? = null

    /**
     * Pendant une assignation, aucun plugin ne reçoit d'événement, sauf le gestionnaire pour les touches
     * system (si ce n'est pas lui qu'on configure). Renvoie vrai si l'événement est consommé.
     */
    private fun captureBinding(event: ApcEvent): Boolean {
        val (h, field) = learning ?: return false
        if (event is ButtonEvent && event.button == Button.SHIFT) return false
        val accepts = if (h.id == managerId) field.accepts else field.accepts - InputKind.SYSTEM
        val binding = InputBinding.capture(event)
        if (binding != null && binding.kind in accepts) {
            assignBinding(h, field, binding)
            return true
        }
        // Touche system non assignable ici : le gestionnaire reste maître.
        return !(event is ButtonEvent && event.button.group == Button.Group.SYSTEM)
    }

    private fun assignBinding(h: PluginHandle, field: BindingField, binding: InputBinding) {
        val config = h.config ?: return cancelLearnNow()
        // Une même entrée ne déclenche qu'une fonction d'un plugin : on libère l'ancienne.
        config.spec.fields.filterIsInstance<BindingField>()
            .filter { it !== field && config[it] == binding }
            .forEach {
                config[it] = InputBinding.None
                log.info("${h.manifest.name} : « ${it.label} » libérée (${binding.label} réassignée)")
            }
        config[field] = binding
        if (binding !is InputBinding.Knob) swallowRelease = binding
        log.info("${h.manifest.name} : « ${field.label} » -> ${binding.label}")
        cancelLearnNow()
        call(h) { it.onConfigChanged() }
        publish()
    }

    private fun isReleaseOf(event: ApcEvent, binding: InputBinding): Boolean = when (binding) {
        is InputBinding.Pad -> event is PadEvent && !event.pressed && event.x == binding.x && event.y == binding.y
        is InputBinding.Btn -> event is ButtonEvent && !event.pressed && event.button == binding.button
        is InputBinding.Key -> event is KeyEvent && !event.pressed && event.note == binding.note
        else -> false
    }

    private fun cancelLearnNow() {
        learning = null
        _learning.value = null
    }

    /** Démarre l'écoute pour assigner une entrée au champ [key] (BindingField) du plugin [id]. */
    fun learn(id: String, key: String) = post {
        val h = handles[id]?.takeIf { it.config != null } ?: return@post
        val field = h.config?.spec?.fields?.firstOrNull { it.key == key } as? BindingField ?: return@post
        learning = h to field
        _learning.value = LearnState(id, field.key)
        log.info("${h.manifest.name} : appuie sur l'entrée à assigner à « ${field.label} »…")
    }

    fun cancelLearn() = post { cancelLearnNow() }

    // --- gestion (thread principal) --------------------------------------------

    private fun ordered(): List<PluginHandle> =
        settings.plugins.keys.mapNotNull(handles::get) + handles.values.filter { it.id !in settings.plugins }

    private fun settingsOf(id: String) = settings.plugins[id] ?: PluginSettings()
    /** Décidé par le plugin lui-même (ApcPlugin.listensInBackground). */
    private fun isListening(id: String): Boolean {
        if (id == managerId) return false
        val plugin = handles[id]?.instance ?: return false
        return try {
            plugin.listensInBackground
        } catch (t: Throwable) {
            false
        }
    }

    /** Plugins à l'écoute au dernier tick : un changement met à jour l'interface et le menu. */
    private var lastListening: Set<String> = emptySet()

    private fun checkListeningChanged() {
        val now = handles.keys.filterTo(HashSet(), ::isListening)
        if (now != lastListening) {
            lastListening = now
            refreshManager()
            publish()
        }
    }

    private fun updateSettings(change: (Settings) -> Settings) {
        settings = change(settings)
        _settings.value = settings
        runCatching { storage.saveSettings(settings) }.onFailure { log.error("sauvegarde de apcdeck.json impossible", it) }
    }

    private fun updatePlugin(id: String, change: (PluginSettings) -> PluginSettings) =
        updateSettings { it.copy(plugins = it.plugins + (id to change(settingsOf(id)))) }

    private fun register(h: PluginHandle) {
        require(h.id !in handles) { "id '${h.id}' déjà utilisé" }
        handles[h.id] = h
        if (h.id !in settings.plugins) updatePlugin(h.id) { it }
    }

    private fun enable(h: PluginHandle) {
        if (h.instance != null) return
        try {
            val plugin = h.instantiate(storage.cacheDir)
            h.instance = plugin
            val config = JsonPluginConfig(storage.configFile(h.id), plugin.configSpec)
            val ctx = PluginContextImpl(this, h, config, JsonDataStore(storage.pluginDataDir(h.id)), logs.logger(h.id))
            h.config = config
            h.context = ctx
            plugin.attach(ctx)
            plugin.onLoad()
            h.status = PluginStatus.ENABLED
            h.error = null
            log.info("${h.manifest.name} ${h.manifest.version} chargé")
        } catch (t: Throwable) {
            h.release()
            h.status = PluginStatus.ERROR
            h.error = t.message ?: t::class.simpleName
            log.error("chargement de ${h.id} impossible", t)
        }
    }

    private fun disable(h: PluginHandle) {
        if (learning?.first === h) cancelLearnNow()
        if (h.instance != null) {
            if (foregroundId == h.id) {
                if (h.id != managerId) activateNow(managerId) else foregroundId = null
            }
            if (previousId == h.id) previousId = null
            call(h) { it.onUnload() }
        }
        h.release()
        if (h.status != PluginStatus.ERROR) h.status = PluginStatus.DISABLED
    }

    private fun activateNow(id: String) {
        val h = handles[id]?.takeIf { it.instance != null } ?: return
        if (id == foregroundId) return
        foregroundId?.let { old ->
            handles[old]?.let { call(it) { p -> p.onDeactivate() } }
            if (old != managerId) previousId = old
        }
        foregroundId = id
        paused = false
        surface.clear()
        log.debug("premier plan : $id")
        call(h) { it.onActivate() }
        publish()
    }

    /** Prévient le gestionnaire d'un changement dans les plugins, puis redessine le menu s'il est affiché. */
    private fun refreshManager() {
        handles[managerId]?.let { h -> call(h) { (it as? ManagerPlugin)?.onPluginsChanged() } }
        if (foregroundId == managerId && !paused) {
            surface.clear()
            handles[managerId]?.let { h -> call(h) { it.onActivate() } }
        }
    }

    private fun toggleHomeNow() {
        val prev = previousId
        if (foregroundId == managerId && prev != null && handles[prev]?.instance != null) activateNow(prev)
        else activateNow(managerId)
    }

    private fun togglePauseNow() {
        val h = foregroundId?.takeIf { it != managerId }?.let(handles::get) ?: return
        surface.clear()
        paused = !paused
        if (paused) {
            call(h) { it.onPause() }
            for (y in 1..3) {
                surface.pad(2, y, PadColor.WHITE, Effect.PULSE)
                surface.pad(5, y, PadColor.WHITE, Effect.PULSE)
            }
        } else {
            call(h) { it.onResume() }
        }
        log.info("${h.id} : ${if (paused) "pause" else "reprise"}")
        publish()
    }


    private fun setEnabledNow(id: String, enabled: Boolean) {
        val h = handles[id] ?: return
        if (id == managerId && !enabled) return
        updatePlugin(id) { it.copy(enabled = enabled) }
        if (enabled) enable(h) else disable(h)
        refreshManager()
        publish()
    }

    private fun reloadNow(id: String) {
        val h = handles[id] ?: return
        val wasForeground = foregroundId == id
        disable(h)
        val source = h.source
        if (source is PluginSource.Jar) {
            try {
                h.manifest = readManifest(source.path).also { require(it.id == id) { "l'id du jar a changé (${it.id})" } }
                h.stamp = stampOf(source.path)
            } catch (t: Throwable) {
                h.status = PluginStatus.ERROR
                h.error = t.message
                log.error("rechargement de $id impossible", t)
                publish()
                return
            }
        }
        h.status = PluginStatus.DISABLED
        enable(h)
        if (wasForeground) activateNow(id)
        refreshManager()
        publish()
    }

    private fun installNow(src: Path) {
        val manifest = try {
            readManifest(src)
        } catch (t: Throwable) {
            log.error("${src.name} n'est pas un plugin valide", t)
            return
        }
        val existing = handles[manifest.id]
        if (existing?.isBuiltin == true) {
            log.error("'${manifest.id}' est un plugin intégré, installation refusée")
            return
        }
        val target = storage.pluginsDir.resolve("${manifest.id}.jar").toAbsolutePath().normalize()
        val wasForeground = foregroundId == manifest.id
        existing?.let { old ->
            disable(old)
            handles.remove(old.id)
            (old.source as? PluginSource.Jar)?.path?.takeIf { it.absolute() != target.absolute() }?.deleteIfExists()
        }
        try {
            if (src.absolute() != target.absolute()) Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            log.error("copie de ${src.name} impossible", t)
            return
        }
        val h = PluginHandle(manifest, PluginSource.Jar(target))
        register(h)
        if (settingsOf(h.id).enabled) enable(h)
        if (wasForeground) activateNow(h.id)
        log.info("${manifest.name} ${manifest.version} ${if (existing == null) "installé" else "mis à jour"}")
        refreshManager()
        publish()
    }

    private fun uninstallNow(id: String) {
        val h = handles[id] ?: return
        val jar = (h.source as? PluginSource.Jar)?.path ?: return log.warn("$id est intégré, il ne peut pas être supprimé")
        disable(h)
        handles.remove(id)
        runCatching { jar.deleteIfExists() }.onFailure { log.error("suppression de ${jar.name} impossible", it) }
        updateSettings { it.copy(plugins = it.plugins - id) }
        log.info("$id désinstallé (configuration et données conservées)")
        notifyRemoved(id)
        refreshManager()
        publish()
    }

    /** Le gestionnaire oublie le plugin (placements dans les pages…). */
    private fun notifyRemoved(id: String) {
        handles[managerId]?.let { h -> call(h) { (it as? ManagerPlugin)?.onPluginRemoved(id) } }
    }

    private fun publish() {
        _plugins.value = ordered().map { h ->
            val m = h.manifest
            PluginView(
                id = h.id, name = m.name, version = m.version, author = m.author, description = m.description,
                color = m.color, status = h.status, error = h.error, builtin = h.isBuiltin, manager = h.id == managerId,
                listening = isListening(h.id), foreground = h.id == foregroundId, paused = paused && h.id == foregroundId,
                fields = h.config?.spec?.fields.orEmpty(), config = h.config?.snapshot().orEmpty(),
                dataDir = storage.dataDir.resolve(h.id), instance = h.instance,
                // Le paramètre de version change à chaque chargement : la page est rechargée après une mise à jour.
                webUrl = h.instance?.takeIf { h.hasWeb }?.let { web.urlFor(h.id, "${System.identityHashCode(it)}") },
                repository = m.repository,
                updateUrl = m.updateUrl,
                jar = (h.source as? PluginSource.Jar)?.path,
            )
        }
    }

    // --- dossier plugins/ ------------------------------------------------------

    private var rescanTask: ScheduledFuture<*>? = null

    private fun watchPluginsDir() {
        val watcher = FileSystems.getDefault().newWatchService()
        storage.pluginsDir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        Thread({
            while (true) {
                val key = try {
                    watcher.take()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: ClosedWatchServiceException) {
                    return@Thread
                }
                val touched = key.pollEvents().any { (it.context() as? Path)?.extension.equals("jar", ignoreCase = true) }
                key.reset()
                if (touched) scheduleRescan()
            }
        }, "apc-plugins-watch").apply { isDaemon = true }.start()
    }

    /** Regroupe les événements (une copie de jar en produit plusieurs) avant de relire le dossier. */
    @Synchronized
    private fun scheduleRescan() {
        if (executor.isShutdown) return
        rescanTask?.cancel(false)
        rescanTask = executor.schedule({ guarded { rescanNow() }; flush() }, 800, TimeUnit.MILLISECONDS)
    }

    private fun rescanNow() {
        val jars = listJars(storage).toSet()
        handles.values.filter { (it.source as? PluginSource.Jar)?.path?.let { p -> p !in jars } == true }.forEach { h ->
            disable(h)
            handles.remove(h.id)
            log.info("${h.manifest.name} retiré (jar supprimé du dossier)")
            notifyRemoved(h.id)
        }
        for (jar in jars) {
            val known = handles.values.firstOrNull { (it.source as? PluginSource.Jar)?.path == jar }
            if (known == null) {
                val manifest = try {
                    readManifest(jar)
                } catch (t: Throwable) {
                    log.warn("${jar.name} ignoré : ${t.message}")
                    continue
                }
                if (manifest.id in handles) {
                    log.error("${jar.name} : l'id '${manifest.id}' est déjà utilisé par un autre plugin")
                    continue
                }
                val h = PluginHandle(manifest, PluginSource.Jar(jar))
                register(h)
                if (settingsOf(h.id).enabled) enable(h)
                log.info("${manifest.name} ${manifest.version} détecté dans le dossier")
            } else if (stampOf(jar) != known.stamp) {
                log.info("${known.manifest.name} : jar modifié, rechargement")
                reloadNow(known.id)
            }
        }
        refreshManager()
        publish()
    }

    private inner class HostImpl : Host {
        override val foregroundId: String? get() = this@Engine.foregroundId
        override val previousId: String? get() = this@Engine.previousId
        override val isPaused: Boolean get() = paused

        override fun plugins(): List<PluginInfo> = ordered()
            .filter { it.instance != null && it.id != managerId }
            .map {
                PluginInfo(it.id, it.manifest.name, it.manifest.description, PadColor(it.manifest.color),
                    isListening(it.id), it.id == this@Engine.foregroundId)
            }

        override fun activate(id: String) = activateNow(id)
        override fun goHome() = activateNow(managerId)
        override fun toggleHome() = toggleHomeNow()
        override fun togglePause() = togglePauseNow()
    }
}

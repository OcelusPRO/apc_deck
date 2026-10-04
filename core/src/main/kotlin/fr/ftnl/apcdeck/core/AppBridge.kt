package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.BindingField
import fr.ftnl.apcdeck.api.BoolField
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.ChoiceField
import fr.ftnl.apcdeck.api.ColorField
import fr.ftnl.apcdeck.api.ConfigField
import fr.ftnl.apcdeck.api.DecimalField
import fr.ftnl.apcdeck.api.InputBinding
import fr.ftnl.apcdeck.api.IntField
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.KnobEvent
import fr.ftnl.apcdeck.api.PadColor
import fr.ftnl.apcdeck.api.PadEvent
import fr.ftnl.apcdeck.api.TextField
import fr.ftnl.apcdeck.core.builtin.PageId
import fr.ftnl.apcdeck.core.builtin.PagerLayout
import fr.ftnl.apcdeck.core.builtin.PagerPlugin
import fr.ftnl.apcdeck.core.builtin.Slot
import fr.ftnl.apcdeck.core.device.DeviceMode
import fr.ftnl.apcdeck.core.device.KnobMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes

/**
 * Relie le moteur à l'interface web : pousse l'état (événements SSE) et exécute les commandes.
 * Événements : boot, plugins, device, settings, leds, input, learning, pager, update, logs (complet), log (une ligne).
 * boot (identifiant de ce lancement) permet à une page restée ouverte de se recharger après un redémarrage.
 */
class AppBridge(private val engine: Engine, private val updater: Updates? = null) : AppRoutes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val web get() = engine.web

    private val bootId = UUID.randomUUID().toString()

    private val pager: PagerPlugin? get() = engine.plugins.value.firstOrNull { it.manager }?.instance as? PagerPlugin

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        web.app = this
        watch(engine.plugins.map { "plugins" to pluginsJson(it) })
        watch(engine.deviceStatus.map { "device" to deviceJson(it) })
        watch(engine.settingsState.map { "settings" to settingsJson(it) })
        watch(engine.surface.snapshot.map { "leds" to ledsJson(it) })
        watch(engine.input.map { "input" to inputJson(it) })
        watch(engine.learningState.map { "learning" to learningJson(it) })
        watch(
            engine.plugins.map { list -> list.firstOrNull { it.manager }?.instance as? PagerPlugin }
                .distinctUntilChanged()
                .flatMapLatest { it?.layout ?: flowOf(null) }
                .map { "pager" to pagerJson(it) },
        )
        updater?.let { u -> watch(u.state.map { "update" to updateJson(it) }) }
        engine.logs.listeners += { line -> web.emitApp("log", logJson(line).toString()) }
    }

    private fun watch(flow: Flow<Pair<String, JsonElement>>) {
        scope.launch { flow.collect { (event, json) -> web.emitApp(event, json.toString()) } }
    }

    override fun snapshot(): List<Pair<String, String>> = listOf(
        "boot" to JsonPrimitive(bootId),
        "plugins" to pluginsJson(engine.plugins.value),
        "device" to deviceJson(engine.deviceStatus.value),
        "settings" to settingsJson(engine.settingsState.value),
        "leds" to ledsJson(engine.surface.snapshot.value),
        "input" to inputJson(engine.input.value),
        "learning" to learningJson(engine.learningState.value),
        "pager" to pagerJson(pager?.layout?.value),
        "update" to (updater?.state?.value?.let(::updateJson) ?: JsonNull),
        "logs" to JsonArray(engine.logs.lines.value.map(::logJson)),
    ).map { (event, json) -> event to json.toString() }

    // --- commandes ---------------------------------------------------------------

    override fun command(name: String, body: ByteArray, query: Map<String, String>): Result<String?> = runCatching {
        if (name == "install") return@runCatching install(body, query["name"])
        val json = body.decodeToString().ifBlank { "{}" }.let { JSON.parseToJsonElement(it).jsonObject }
        fun str(key: String): String = json[key]?.jsonPrimitive?.content ?: error("paramètre '$key' manquant")
        fun int(key: String, obj: JsonObject = json): Int = obj[key]?.jsonPrimitive?.intOrNull ?: error("paramètre '$key' manquant")
        fun bool(key: String): Boolean = json[key]?.jsonPrimitive?.booleanOrNull ?: error("paramètre '$key' manquant")
        fun slot(obj: JsonObject = json) = Slot(PageId(int("row", obj), int("col", obj)), int("x", obj), int("y", obj))

        when (name) {
            "reconnect" -> engine.reconnect()
            "virtual" -> engine.setVirtual(bool("enabled"))
            "mode" -> DeviceMode.valueOf(str("mode")).let {
                engine.setDeviceMode(it, if (it == DeviceMode.GENERIC) KnobMode.ABSOLUTE else KnobMode.RELATIVE)
            }
            "rescan" -> engine.rescan()
            "activate" -> engine.activate(str("id"))
            "enable" -> engine.setEnabled(str("id"), bool("enabled"))
            "reload" -> engine.reload(str("id"))
            "uninstall" -> engine.uninstall(str("id"))
            "config" -> {
                val value = json["value"] ?: JsonNull
                engine.setConfigByKey(str("id"), str("key")) { field -> decode(field, value) }
            }
            "learn" -> engine.learn(str("id"), str("key"))
            "cancelLearn" -> engine.cancelLearn()
            "simulate" -> engine.simulate(
                when (str("type")) {
                    "pad" -> PadEvent(int("x"), int("y"), bool("pressed"))
                    "button" -> ButtonEvent(Button.valueOf(str("button")), bool("pressed"))
                    "key" -> KeyEvent(int("note"), bool("pressed"), if (bool("pressed")) 100 else 0)
                    "knob" -> {
                        val index = int("index")
                        val delta = int("delta")
                        val current = engine.input.value.knobs.getOrElse(index) { 64 }
                        KnobEvent(index, delta, (current + delta).coerceIn(0, 127))
                    }
                    else -> error("type d'événement inconnu")
                },
            )
            "pagerPage" -> pager?.showPage(PageId(int("row"), int("col")))
            "pagerPlace" -> pager?.place(slot(), str("id"))
            "pagerMove" -> pager?.move(slot(json["from"]!!.jsonObject), slot(json["to"]!!.jsonObject))
            "pagerClear" -> pager?.clear(slot())
            "checkUpdate" -> updater?.check(manual = true)
            "installUpdate" -> updater?.install() ?: error("mises à jour indisponibles")
            "installPluginUpdate" -> updater?.installPlugin(str("id")) ?: error("mises à jour indisponibles")
            "installPluginUpdates" -> updater?.installPlugins() ?: error("mises à jour indisponibles")
            "openFolder" -> {
                val id = json["id"]?.jsonPrimitive?.content
                val dir = if (id == null) engine.storage.home else engine.storage.pluginDataDir(id)
                engine.platform.openFolder(dir.createDirectories().toAbsolutePath())
            }
            else -> error("commande inconnue : $name")
        }
        null
    }

    /** Jar envoyé par l'interface (glisser-déposer ou sélecteur de fichier). */
    private fun install(bytes: ByteArray, name: String?): String? {
        require(name != null && name.endsWith(".jar", ignoreCase = true)) { "un fichier .jar est attendu" }
        require(bytes.isNotEmpty()) { "fichier vide" }
        val temp = engine.storage.cacheDir.resolve("upload-${UUID.randomUUID()}.jar")
        temp.writeBytes(bytes)
        // Validation immédiate : l'interface affiche l'erreur avec le vrai nom du fichier.
        val manifest = try {
            readManifest(temp)
        } catch (t: Throwable) {
            temp.deleteIfExists()
            throw IllegalArgumentException("$name n'est pas un plugin valide : ${t.message ?: t::class.simpleName}")
        }
        // Plugin déjà présent (même id) : c'est une mise à jour, l'ancien jar est remplacé.
        val previous = engine.plugins.value.firstOrNull { it.id == manifest.id }
        require(previous?.builtin != true) { "'${manifest.id}' est un plugin intégré, il ne peut pas être remplacé" }
        engine.install(temp, deleteAfter = true)
        return buildJsonObject {
            put("id", manifest.id)
            put("name", manifest.name)
            put("version", manifest.version)
            put("previousVersion", previous?.version)
        }.toString()
    }

    private fun decode(field: ConfigField<*>, value: JsonElement): Any? {
        val p = value as? JsonPrimitive ?: return null
        return when (field) {
            is BoolField -> p.booleanOrNull
            is IntField -> p.intOrNull?.let { v -> field.range?.let { v.coerceIn(it) } ?: v }
            is DecimalField -> p.doubleOrNull?.let { v -> field.range?.let { v.coerceIn(it) } ?: v }
            is TextField -> p.takeIf { it.isString }?.content
            is ChoiceField -> p.content.takeIf { it in field.options }
            is ColorField -> p.intOrNull?.takeIf { it in 0..127 }?.let(::PadColor)
            is BindingField -> InputBinding.decode(p.content)?.takeIf { it == InputBinding.None || it.kind in field.accepts }
        }
    }

    // --- JSON de l'état -------------------------------------------------------------

    private fun pluginsJson(list: List<PluginView>) = buildJsonArray {
        list.forEach { p ->
            add(buildJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("version", p.version)
                put("author", p.author)
                put("description", p.description)
                put("color", p.color)
                put("status", p.status.name)
                put("error", p.error)
                put("builtin", p.builtin)
                put("manager", p.manager)
                put("listening", p.listening)
                put("foreground", p.foreground)
                put("paused", p.paused)
                put("webUrl", p.webUrl)
                putJsonArray("fields") { p.fields.forEach { add(fieldJson(it)) } }
                put("config", JsonObject(p.config.mapValues { (_, v) -> valueJson(v) }))
            })
        }
    }

    private fun fieldJson(f: ConfigField<*>) = buildJsonObject {
        put("key", f.key)
        put("label", f.label)
        put("description", f.description)
        when (f) {
            is BoolField -> put("type", "bool")
            is IntField -> {
                put("type", "int")
                f.range?.let { put("min", it.first); put("max", it.last) }
            }
            is DecimalField -> {
                put("type", "decimal")
                f.range?.let { put("min", it.start); put("max", it.endInclusive) }
            }
            is TextField -> {
                put("type", "text")
                put("multiline", f.multiline)
            }
            is ChoiceField -> {
                put("type", "choice")
                putJsonArray("options") { f.options.forEach { add(JsonPrimitive(it)) } }
            }
            is ColorField -> put("type", "color")
            is BindingField -> {
                put("type", "binding")
                putJsonArray("accepts") { f.accepts.forEach { add(JsonPrimitive(it.name)) } }
            }
        }
    }

    private fun valueJson(value: Any): JsonElement = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is PadColor -> JsonPrimitive(value.index)
        is InputBinding -> buildJsonObject {
            put("encoded", value.encode())
            put("label", value.label)
        }
        else -> JsonPrimitive(value.toString())
    }

    private fun deviceJson(d: DeviceStatus) = buildJsonObject {
        put("connected", d.connected)
        put("detail", d.detail)
        put("virtual", d.virtual)
    }

    private fun settingsJson(s: Settings) = buildJsonObject {
        put("mode", s.mode.name)
        put("knobs", s.knobs.name)
        put("virtual", s.virtualApc)
        putJsonArray("modes") { DeviceMode.entries.forEach { add(JsonPrimitive(it.name)) } }
    }

    private fun ledsJson(s: LedSnapshot) = buildJsonObject {
        putJsonArray("colors") { s.padColors.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("effects") { s.padEffects.forEach { add(JsonPrimitive(it.channel)) } }
        put("buttons", JsonObject(s.buttons.entries.associate { (b, state) -> b.name to JsonPrimitive(state.velocity) }))
    }

    private fun inputJson(i: InputState) = buildJsonObject {
        putJsonArray("pads") { i.pads.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("buttons") { i.buttons.forEach { add(JsonPrimitive(it.name)) } }
        putJsonArray("notes") { i.notes.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("knobs") { i.knobs.forEach { add(JsonPrimitive(it)) } }
    }

    private fun learningJson(l: LearnState?): JsonElement = l?.let {
        buildJsonObject {
            put("pluginId", it.pluginId)
            put("fieldKey", it.fieldKey)
        }
    } ?: JsonNull

    private fun pagerJson(layout: PagerLayout?): JsonElement = layout?.let { l ->
        buildJsonObject {
            put("current", buildJsonObject { put("row", l.current.row); put("col", l.current.col) })
            putJsonArray("slots") {
                l.slots.forEach { (slot, id) ->
                    add(buildJsonObject {
                        put("row", slot.page.row)
                        put("col", slot.page.col)
                        put("x", slot.x)
                        put("y", slot.y)
                        put("id", id)
                    })
                }
            }
        }
    } ?: JsonNull

    private fun updateJson(u: UpdateState) = buildJsonObject {
        put("current", u.current)
        put("status", u.status.name)
        put("latest", u.latest)
        put("pageUrl", u.pageUrl)
        put("assetName", u.assetName)
        put("notes", u.notes)
        put("progress", u.progress)
        put("error", u.error)
        put("checkedAt", u.checkedAt)
        putJsonArray("plugins") {
            u.plugins.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("current", p.current)
                    put("latest", p.latest)
                    put("pageUrl", p.pageUrl)
                    put("installing", p.installing)
                    put("error", p.error)
                })
            }
        }
    }

    private fun logJson(line: LogLine) = buildJsonObject {
        put("text", line.toString())
        put("level", line.level.name)
    }
}

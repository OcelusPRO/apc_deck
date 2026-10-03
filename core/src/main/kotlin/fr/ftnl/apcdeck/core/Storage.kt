package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.ConfigField
import fr.ftnl.apcdeck.api.ConfigSpec
import fr.ftnl.apcdeck.api.DataStore
import fr.ftnl.apcdeck.api.PluginConfig
import fr.ftnl.apcdeck.core.device.DeviceMode
import fr.ftnl.apcdeck.core.device.KnobMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

val JSON: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class PluginSettings(val enabled: Boolean = true)

@Serializable
data class Settings(
    val mode: DeviceMode = DeviceMode.ABLETON_ALT,
    val knobs: KnobMode = KnobMode.RELATIVE,
    val fps: Int = 30,
    /** Id du plugin gestionnaire (doit hériter de ManagerPlugin). */
    val manager: String = "pager",
    /** Ordre = ordre du menu. */
    val plugins: Map<String, PluginSettings> = emptyMap(),
    /** Port de l'interface (un port libre est pris s'il est occupé). */
    val uiPort: Int = 47800,
    /** Jeton de l'adresse de l'interface, généré au premier lancement. */
    val uiToken: String = "",
)

/**
 * Arborescence du dossier de l'application :
 * apcdeck.json, plugins/ (jars), config/<id>.json, data/<id>/, .cache/ (copies des jars chargés).
 */
class Storage(home: Path) {
    val home: Path = home.toAbsolutePath().normalize()
    val pluginsDir: Path = home.resolve("plugins")
    val configDir: Path = home.resolve("config")
    val dataDir: Path = home.resolve("data")
    val cacheDir: Path = home.resolve(".cache")
    private val settingsFile = home.resolve("apcdeck.json")

    init {
        listOf(pluginsDir, configDir, dataDir, cacheDir).forEach { it.createDirectories() }
    }

    fun loadSettings(): Settings =
        if (settingsFile.exists()) JSON.decodeFromString(settingsFile.readText()) else Settings()

    fun saveSettings(settings: Settings) = writeAtomic(settingsFile, JSON.encodeToString(settings))

    fun configFile(id: String): Path = configDir.resolve("$id.json")
    fun pluginDataDir(id: String): Path = dataDir.resolve(id).createDirectories()
}

/** Écriture via fichier temporaire : un crash pendant l'écriture ne corrompt pas l'ancien fichier. */
fun writeAtomic(file: Path, text: String) {
    val tmp = file.resolveSibling("${file.fileName}.tmp")
    tmp.writeText(text)
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

private fun readJsonObject(file: Path): Map<String, JsonElement> =
    if (file.exists()) runCatching { JSON.parseToJsonElement(file.readText()) as JsonObject }.getOrNull() ?: emptyMap()
    else emptyMap()

private fun JsonElement.toRaw(): Any? = (this as? JsonPrimitive)?.let {
    when {
        it.isString -> it.content
        else -> it.booleanOrNull ?: it.longOrNull ?: it.doubleOrNull
    }
}

private fun Any.toJson(): JsonPrimitive = when (this) {
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

/** Configuration d'un plugin, persistée dans config/<id>.json. */
class JsonPluginConfig(private val file: Path, val spec: ConfigSpec) : PluginConfig {
    private val raw: MutableMap<String, Any> =
        readJsonObject(file).mapNotNull { (k, v) -> v.toRaw()?.let { k to it } }.toMap(LinkedHashMap())

    override fun <T : Any> get(field: ConfigField<T>): T = field.decode(raw[field.key]) ?: field.default

    override fun <T : Any> set(field: ConfigField<T>, value: T) {
        raw[field.key] = field.encode(value)
        save()
    }

    /** Valeur courante d'un champ quelconque du schéma (pour l'interface). */
    fun valueOf(field: ConfigField<*>): Any = field.decode(raw[field.key]) ?: field.default

    /** Écriture depuis l'interface, où le type n'est connu qu'à l'exécution. */
    fun setUnchecked(field: ConfigField<*>, value: Any) {
        @Suppress("UNCHECKED_CAST")
        raw[field.key] = (field as ConfigField<Any>).encode(value)
        save()
    }

    fun snapshot(): Map<String, Any> = spec.fields.associate { it.key to valueOf(it) }

    private fun save() = writeAtomic(file, JSON.encodeToString(JsonObject(raw.mapValues { it.value.toJson() })))
}

/** Données d'un plugin, persistées dans data/<id>/store.json. */
class JsonDataStore(override val directory: Path) : DataStore {
    private val file = directory.resolve("store.json")
    private val values: MutableMap<String, String> =
        readJsonObject(file).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
            .toMap(LinkedHashMap())

    override val keys: Set<String> get() = values.keys.toSet()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        writeAtomic(file, JSON.encodeToString(JsonObject(values.mapValues { JsonPrimitive(it.value) })))
    }
}

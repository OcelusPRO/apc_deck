package fr.ftnl.apcdeck.api

/**
 * Champ de configuration typé. L'application génère le formulaire et persiste la valeur.
 * Valeurs brutes stockées : Boolean, Long, Double ou String.
 */
sealed class ConfigField<T : Any>(
    val key: String,
    val label: String,
    val description: String,
    val default: T,
) {
    /** Convertit une valeur brute stockée (ou null) en valeur typée ; null si invalide. */
    abstract fun decode(raw: Any?): T?
    abstract fun encode(value: T): Any
}

class BoolField(key: String, label: String, description: String, default: Boolean) :
    ConfigField<Boolean>(key, label, description, default) {
    override fun decode(raw: Any?): Boolean? = raw as? Boolean
    override fun encode(value: Boolean): Any = value
}

class IntField(key: String, label: String, description: String, default: Int, val range: IntRange?) :
    ConfigField<Int>(key, label, description, default) {
    override fun decode(raw: Any?): Int? =
        (raw as? Number)?.toInt()?.let { v -> range?.let { v.coerceIn(it) } ?: v }

    override fun encode(value: Int): Any = value.toLong()
}

class DecimalField(
    key: String, label: String, description: String, default: Double,
    val range: ClosedFloatingPointRange<Double>?,
) : ConfigField<Double>(key, label, description, default) {
    override fun decode(raw: Any?): Double? =
        (raw as? Number)?.toDouble()?.let { v -> range?.let { v.coerceIn(it) } ?: v }

    override fun encode(value: Double): Any = value
}

class TextField(key: String, label: String, description: String, default: String, val multiline: Boolean) :
    ConfigField<String>(key, label, description, default) {
    override fun decode(raw: Any?): String? = raw as? String
    override fun encode(value: String): Any = value
}

class ChoiceField(key: String, label: String, description: String, default: String, val options: List<String>) :
    ConfigField<String>(key, label, description, default) {
    init {
        require(default in options) { "valeur par défaut '$default' absente des options de '$key'" }
    }

    override fun decode(raw: Any?): String? = (raw as? String)?.takeIf { it in options }
    override fun encode(value: String): Any = value
}

class ColorField(key: String, label: String, description: String, default: PadColor) :
    ConfigField<PadColor>(key, label, description, default) {
    override fun decode(raw: Any?): PadColor? = (raw as? Number)?.toInt()?.takeIf { it in 0..127 }?.let(::PadColor)
    override fun encode(value: PadColor): Any = value.index.toLong()
}

/**
 * Schéma de configuration d'un plugin. À déclarer comme `object` :
 * ```
 * object Settings : ConfigSpec() {
 *     val speed = int("speed", "Vitesse", default = 5, range = 1..10)
 * }
 * ...
 * val s = ctx.config[Settings.speed]
 * ```
 */
open class ConfigSpec {
    private val _fields = mutableListOf<ConfigField<*>>()
    val fields: List<ConfigField<*>> get() = _fields

    private fun <F : ConfigField<*>> add(field: F): F {
        require(_fields.none { it.key == field.key }) { "clé de configuration en double : ${field.key}" }
        _fields += field
        return field
    }

    protected fun bool(key: String, label: String, default: Boolean = false, description: String = ""): BoolField =
        add(BoolField(key, label, description, default))

    protected fun int(
        key: String, label: String, default: Int = 0, range: IntRange? = null, description: String = "",
    ): IntField = add(IntField(key, label, description, default, range))

    protected fun decimal(
        key: String, label: String, default: Double = 0.0,
        range: ClosedFloatingPointRange<Double>? = null, description: String = "",
    ): DecimalField = add(DecimalField(key, label, description, default, range))

    protected fun text(
        key: String, label: String, default: String = "", multiline: Boolean = false, description: String = "",
    ): TextField = add(TextField(key, label, description, default, multiline))

    protected fun choice(
        key: String, label: String, options: List<String>, default: String = options.first(), description: String = "",
    ): ChoiceField = add(ChoiceField(key, label, description, default, options))

    protected fun color(
        key: String, label: String, default: PadColor = PadColor.WHITE, description: String = "",
    ): ColorField = add(ColorField(key, label, description, default))

    /**
     * Fonction à laquelle l'utilisateur assigne une entrée (bouton « Assigner » puis appui sur l'APC).
     * [accepts] limite les entrées possibles ; SYSTEM n'est accepté que pour le plugin gestionnaire.
     */
    protected fun binding(
        key: String, label: String, default: InputBinding = InputBinding.None,
        accepts: Set<InputKind> = InputKind.PLUGIN, description: String = "",
    ): BindingField = add(BindingField(key, label, description, default, accepts))

    companion object {
        val EMPTY: ConfigSpec = ConfigSpec()
    }
}

/** Valeurs de configuration courantes d'un plugin (lecture/écriture, persistées automatiquement). */
interface PluginConfig {
    operator fun <T : Any> get(field: ConfigField<T>): T
    operator fun <T : Any> set(field: ConfigField<T>, value: T)
}

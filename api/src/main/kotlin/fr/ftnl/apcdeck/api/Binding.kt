package fr.ftnl.apcdeck.api

/** Catégories d'entrées assignables. SYSTEM est réservé au plugin gestionnaire. */
enum class InputKind { PAD, BUTTON, SYSTEM, KEY, KNOB;

    companion object {
        /** Ce qu'un plugin ordinaire peut assigner. */
        val PLUGIN: Set<InputKind> = setOf(PAD, BUTTON, KEY, KNOB)
    }
}

/**
 * Touche (ou pad, potar…) assignée à une fonction par l'utilisateur, via le mode « écoute » de l'interface.
 * Dans un plugin : `if (ctx.config[Settings.clear].matches(event)) clear()`.
 */
sealed interface InputBinding {
    val kind: InputKind?
    val label: String
    fun encode(): String

    /** Vrai pour l'appui (pad, bouton, touche) ou pour tout mouvement (potar). Jamais pour un relâchement. */
    fun matches(event: ApcEvent): Boolean

    data object None : InputBinding {
        override val kind: InputKind? = null
        override val label: String = "Non assignée"
        override fun encode(): String = ""
        override fun matches(event: ApcEvent): Boolean = false
    }

    data class Pad(val x: Int, val y: Int) : InputBinding {
        override val kind: InputKind = InputKind.PAD
        override val label: String get() = "Pad ${x + 1}·${y + 1}"
        override fun encode(): String = "pad:$x,$y"
        override fun matches(event: ApcEvent): Boolean = event is PadEvent && event.pressed && event.x == x && event.y == y
    }

    data class Btn(val button: Button) : InputBinding {
        override val kind: InputKind =
            if (button.group == Button.Group.SYSTEM) InputKind.SYSTEM else InputKind.BUTTON
        override val label: String get() = buttonLabel(button)
        override fun encode(): String = "button:${button.name}"
        override fun matches(event: ApcEvent): Boolean = event is ButtonEvent && event.pressed && event.button == button
    }

    data class Key(val note: Int) : InputBinding {
        override val kind: InputKind = InputKind.KEY
        override val label: String get() = "Touche ${noteName(note)} ($note)"
        override fun encode(): String = "key:$note"
        override fun matches(event: ApcEvent): Boolean = event is KeyEvent && event.pressed && event.note == note
    }

    data class Knob(val index: Int) : InputBinding {
        override val kind: InputKind = InputKind.KNOB
        override val label: String get() = "Potar ${index + 1}"
        override fun encode(): String = "knob:$index"
        override fun matches(event: ApcEvent): Boolean = event is KnobEvent && event.index == index
    }

    companion object {
        fun decode(text: String): InputBinding? {
            if (text.isEmpty()) return None
            val (type, value) = text.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return runCatching {
                when (type) {
                    "pad" -> value.split(',').let { (x, y) -> Pad(x.toInt(), y.toInt()) }
                    "button" -> Btn(Button.valueOf(value))
                    "key" -> Key(value.toInt())
                    "knob" -> Knob(value.toInt())
                    else -> null
                }
            }.getOrNull()
        }

        /** Entrée assignable correspondant à un événement (null pour un relâchement ou pour Shift). */
        fun capture(event: ApcEvent): InputBinding? = when (event) {
            is PadEvent -> if (event.pressed) Pad(event.x, event.y) else null
            is ButtonEvent -> if (event.pressed && event.button != Button.SHIFT) Btn(event.button) else null
            is KeyEvent -> if (event.pressed) Key(event.note) else null
            is KnobEvent -> Knob(event.index)
        }

        fun noteName(note: Int): String =
            listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[note % 12] + (note / 12 - 1)

        fun buttonLabel(button: Button): String = when (button.group) {
            Button.Group.TRACK -> "Track ${button.index + 1}"
            Button.Group.SCENE -> "Scène ${button.index + 1}"
            else -> button.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
    }
}

class BindingField(
    key: String, label: String, description: String, default: InputBinding, val accepts: Set<InputKind>,
) : ConfigField<InputBinding>(key, label, description, default) {
    override fun decode(raw: Any?): InputBinding? =
        (raw as? String)?.let(InputBinding::decode)?.takeIf { it == InputBinding.None || it.kind in accepts }

    override fun encode(value: InputBinding): Any = value.encode()
}

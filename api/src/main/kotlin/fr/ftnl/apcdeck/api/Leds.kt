package fr.ftnl.apcdeck.api

/** Index dans la palette 128 couleurs de l'APC (0 = éteint). */
@JvmInline
value class PadColor(val index: Int) {
    init {
        require(index in 0..127) { "index de couleur hors palette : $index" }
    }

    companion object {
        val OFF: PadColor = PadColor(0)
        val GRAY: PadColor = PadColor(2)
        val WHITE: PadColor = PadColor(3)
        val RED: PadColor = PadColor(5)
        val ORANGE: PadColor = PadColor(9)
        val YELLOW: PadColor = PadColor(13)
        val LIME: PadColor = PadColor(17)
        val GREEN: PadColor = PadColor(21)
        val MINT: PadColor = PadColor(29)
        val CYAN: PadColor = PadColor(33)
        val SKY: PadColor = PadColor(37)
        val BLUE: PadColor = PadColor(45)
        val PURPLE: PadColor = PadColor(49)
        val MAGENTA: PadColor = PadColor(53)
        val PINK: PadColor = PadColor(57)

        val NAMED: Map<String, PadColor> = linkedMapOf(
            "off" to OFF, "gray" to GRAY, "white" to WHITE, "red" to RED, "orange" to ORANGE,
            "yellow" to YELLOW, "lime" to LIME, "green" to GREEN, "mint" to MINT, "cyan" to CYAN,
            "sky" to SKY, "blue" to BLUE, "purple" to PURPLE, "magenta" to MAGENTA, "pink" to PINK,
        )
    }
}

/** Rendu d'un pad : la valeur est le canal MIDI envoyé à l'APC. */
enum class Effect(val channel: Int) {
    BRIGHTNESS_10(0), BRIGHTNESS_25(1), BRIGHTNESS_50(2), BRIGHTNESS_65(3),
    BRIGHTNESS_75(4), BRIGHTNESS_90(5), SOLID(6),
    PULSE_1_16(7), PULSE_1_8(8), PULSE_1_4(9), PULSE_1_2(10),
    BLINK_1_24(11), BLINK_1_16(12), BLINK_1_8(13), BLINK_1_4(14), BLINK_1_2(15);

    val isPulse: Boolean get() = channel in 7..10
    val isBlink: Boolean get() = channel in 11..15

    companion object {
        val PULSE: Effect = PULSE_1_4
        val BLINK: Effect = BLINK_1_8
        fun ofChannel(channel: Int): Effect = entries.first { it.channel == channel }
    }
}

/** État d'une LED de bouton (ligne rouge / colonne verte). */
enum class LedState(val velocity: Int) { OFF(0), ON(1), BLINK(2) }

/** Écran LED de l'APC. Un plugin qui n'est pas au premier plan reçoit un écran factice. */
interface Leds {
    fun pad(x: Int, y: Int, color: PadColor, effect: Effect = Effect.SOLID)
    fun button(button: Button, state: LedState)
    fun clear()

    fun row(y: Int, color: PadColor, effect: Effect = Effect.SOLID) {
        for (x in 0 until Grid.COLS) pad(x, y, color, effect)
    }

    fun fill(color: PadColor, effect: Effect = Effect.SOLID) {
        for (y in 0 until Grid.ROWS) row(y, color, effect)
    }
}

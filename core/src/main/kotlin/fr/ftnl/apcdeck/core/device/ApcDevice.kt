package fr.ftnl.apcdeck.core.device

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.Grid
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.KnobEvent
import fr.ftnl.apcdeck.api.PadEvent

/** Mode envoyé par le SysEx "Introduction" ; NONE = ne pas toucher au mode courant. */
enum class DeviceMode(val sysex: Int?) { GENERIC(0x40), ABLETON(0x41), ABLETON_ALT(0x42), NONE(null) }

/** Les potars sont relatifs (1 / 127) dans les modes Ableton, absolus sinon. */
enum class KnobMode { RELATIVE, ABSOLUTE }

/**
 * AKAI APC Key 25 mk2 : décodage des messages reçus en événements, envoi des LED et du mode. Le transport (ports
 * MIDI de la plateforme) est fourni par [backend].
 * [onEvent] est appelé sur un thread MIDI : à re-poster sur le thread principal.
 */
class ApcDevice(private val backend: MidiBackend, private val onEvent: (ApcEvent) -> Unit) {
    @Volatile
    private var connection: MidiConnection? = null
    private val knobValues = IntArray(KNOB_COUNT) { 64 }

    @Volatile
    var knobMode: KnobMode = KnobMode.RELATIVE

    val isOpen: Boolean get() = connection != null

    /** Ouvre les ports ; renvoie une description lisible. Lève une exception si l'APC est introuvable. */
    fun open(mode: DeviceMode): String {
        close()
        val opened = backend.open { message, control -> decode(message, control)?.let(onEvent) }
        try {
            connection = opened
            mode.sysex?.let { sendModeSysex(it) }
        } catch (t: Throwable) {
            close()
            throw t
        }
        return "${opened.description}, mode $mode"
    }

    fun close() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    private fun sendModeSysex(mode: Int) {
        connection?.send(bytes(0xF0, 0x47, 0x7F, DEVICE_ID, 0x60, 0x00, 0x04, mode, 1, 1, 1, 0xF7), control = false)
        Thread.sleep(300)
    }

    // --- entrées -------------------------------------------------------------

    /** Message brut (statut, données 1, données 2) -> événement ; null pour ce qui ne concerne pas l'APC. */
    internal fun decode(message: ByteArray, control: Boolean): ApcEvent? {
        if (message.size < 3) return null
        val command = message[0].toInt() and 0xF0
        val data1 = message[1].toInt() and 0x7F
        val data2 = message[2].toInt() and 0x7F
        return when (command) {
            NOTE_ON, NOTE_OFF -> {
                val pressed = command == NOTE_ON && data2 > 0
                when {
                    !control -> KeyEvent(data1, pressed, data2)
                    data1 < Grid.PADS -> PadEvent(data1 % Grid.COLS, Grid.ROWS - 1 - data1 / Grid.COLS, pressed, data2)
                    else -> BUTTON_BY_NOTE[data1]?.let { ButtonEvent(it, pressed) }
                }
            }
            CONTROL_CHANGE -> when (data1) {
                SUSTAIN_CC -> ButtonEvent(Button.SUSTAIN, data2 >= 64)
                in KNOB_CC_BASE until KNOB_CC_BASE + KNOB_COUNT -> knob(data1 - KNOB_CC_BASE, data2)
                else -> null
            }
            else -> null
        }
    }

    private fun knob(index: Int, raw: Int): KnobEvent {
        val current = knobValues[index]
        val (delta, value) = when (knobMode) {
            KnobMode.RELATIVE -> {
                val d = if (raw < 64) raw else raw - 128
                d to (current + d).coerceIn(0, 127)
            }
            KnobMode.ABSOLUTE -> (raw - current) to raw
        }
        knobValues[index] = value
        return KnobEvent(index, delta, value)
    }

    // --- sorties (LED) -------------------------------------------------------

    /** Lève une exception si l'appareil a disparu. */
    fun setPad(x: Int, y: Int, color: Int, channel: Int) {
        val note = (Grid.ROWS - 1 - y) * Grid.COLS + x
        connection?.send(bytes(NOTE_ON or (channel and 0x0F), note, color), control = true)
    }

    fun setButton(button: Button, velocity: Int) {
        val note = NOTE_BY_BUTTON[button] ?: return
        if (button.hasLed) connection?.send(bytes(NOTE_ON, note, velocity), control = true)
    }

    private companion object {
        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val CONTROL_CHANGE = 0xB0
        const val DEVICE_ID = 0x4E
        const val KNOB_CC_BASE = 48
        const val KNOB_COUNT = 8
        const val SUSTAIN_CC = 64

        val NOTE_BY_BUTTON: Map<Button, Int> = buildMap {
            Button.entries.filter { it.group == Button.Group.TRACK }.forEach { put(it, 64 + it.index) }
            Button.entries.filter { it.group == Button.Group.SCENE }.forEach { put(it, 82 + it.index) }
            put(Button.STOP_ALL, 81)
            put(Button.SHIFT, 98)
            put(Button.PLAY, 91)
            put(Button.REC, 93)
        }
        val BUTTON_BY_NOTE: Map<Int, Button> = NOTE_BY_BUTTON.entries.associate { (b, n) -> n to b }

        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}

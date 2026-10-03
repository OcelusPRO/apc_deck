package fr.ftnl.apcdeck.core.device

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.Grid
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.KnobEvent
import fr.ftnl.apcdeck.api.PadEvent
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage

/** Mode envoyé par le SysEx "Introduction" ; NONE = ne pas toucher au mode courant. */
enum class DeviceMode(val sysex: Int?) { GENERIC(0x40), ABLETON(0x41), ABLETON_ALT(0x42), NONE(null) }

/** Les potars sont relatifs (1 / 127) dans les modes Ableton, absolus sinon. */
enum class KnobMode { RELATIVE, ABSOLUTE }

/**
 * AKAI APC Key 25 mk2 via javax.sound.midi.
 * Ports Windows : "APC Key 25 mk2" (clavier, sustain, SysEx) et "MIDIIN2/MIDIOUT2 (APC Key 25 mk2)"
 * (pads, boutons, potars en entrée ; LED en sortie).
 * [onEvent] est appelé sur un thread MIDI : à re-poster sur le thread principal.
 */
class ApcDevice(private val onEvent: (ApcEvent) -> Unit) {
    private val opened = mutableListOf<MidiDevice>()
    private var mainOut: Receiver? = null
    private var controlOut: Receiver? = null
    private val knobValues = IntArray(KNOB_COUNT) { 64 }

    @Volatile
    var knobMode: KnobMode = KnobMode.RELATIVE

    val isOpen: Boolean get() = controlOut != null

    /** Ouvre les ports ; renvoie une description lisible. Lève une exception si l'APC est introuvable. */
    fun open(mode: DeviceMode): String {
        close()
        val devices = MidiSystem.getMidiDeviceInfo()
            .filter { it.name.contains("APC", ignoreCase = true) }
            .map { MidiSystem.getMidiDevice(it) }
        val inputs = devices.filter { it.maxTransmitters != 0 }
        val outputs = devices.filter { it.maxReceivers != 0 }
        val (inMain, inControl) = split(inputs, "MIDIIN2")
        val (outMain, outControl) = split(outputs, "MIDIOUT2")
        requireNotNull(inMain ?: inControl) { "APC Key 25 mk2 introuvable (branché ? utilisé par un autre programme ?)" }
        requireNotNull(outMain ?: outControl) { "port de sortie de l'APC introuvable" }

        try {
            inMain?.let { listen(it, control = false) }
            inControl?.let { listen(it, control = true) }
            mainOut = outMain?.let(::receiverOf)
            controlOut = outControl?.let(::receiverOf) ?: mainOut
            mode.sysex?.let { sendModeSysex(it) }
        } catch (t: Throwable) {
            close()
            throw t
        }
        return "entrée ${inMain?.deviceInfo?.name} / ${inControl?.deviceInfo?.name}, " +
            "sortie ${outMain?.deviceInfo?.name} / ${outControl?.deviceInfo?.name}, mode $mode"
    }

    fun close() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        mainOut = null
        controlOut = null
    }

    private fun split(devices: List<MidiDevice>, controlTag: String): Pair<MidiDevice?, MidiDevice?> {
        val control = devices.firstOrNull { controlTag in it.deviceInfo.name }
        val main = devices.firstOrNull { it !== control }
        return if (control == null && devices.size > 1) devices[0] to devices[1] else main to control
    }

    private fun listen(device: MidiDevice, control: Boolean) {
        device.open()
        opened += device
        device.transmitter.receiver = object : Receiver {
            override fun send(message: MidiMessage, timeStamp: Long) {
                decode(message, control)?.let(onEvent)
            }

            override fun close() {}
        }
    }

    private fun receiverOf(device: MidiDevice): Receiver {
        device.open()
        opened += device
        return device.receiver
    }

    private fun sendModeSysex(mode: Int) {
        val bytes = bytes(0xF0, 0x47, 0x7F, DEVICE_ID, 0x60, 0x00, 0x04, mode, 1, 1, 1, 0xF7)
        (mainOut ?: controlOut)?.send(SysexMessage(bytes, bytes.size), -1)
        Thread.sleep(300)
    }

    // --- entrées -------------------------------------------------------------

    private fun decode(message: MidiMessage, control: Boolean): ApcEvent? {
        if (message !is ShortMessage) return null
        return when (message.command) {
            ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF -> {
                val pressed = message.command == ShortMessage.NOTE_ON && message.data2 > 0
                val note = message.data1
                when {
                    !control -> KeyEvent(note, pressed, message.data2)
                    note < Grid.PADS -> PadEvent(note % Grid.COLS, Grid.ROWS - 1 - note / Grid.COLS, pressed, message.data2)
                    else -> BUTTON_BY_NOTE[note]?.let { ButtonEvent(it, pressed) }
                }
            }
            ShortMessage.CONTROL_CHANGE -> when (val cc = message.data1) {
                SUSTAIN_CC -> ButtonEvent(Button.SUSTAIN, message.data2 >= 64)
                in KNOB_CC_BASE until KNOB_CC_BASE + KNOB_COUNT -> knob(cc - KNOB_CC_BASE, message.data2)
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
        controlOut?.send(ShortMessage(ShortMessage.NOTE_ON, channel, note, color), -1)
    }

    fun setButton(button: Button, velocity: Int) {
        val note = NOTE_BY_BUTTON[button] ?: return
        if (button.hasLed) controlOut?.send(ShortMessage(ShortMessage.NOTE_ON, 0, note, velocity), -1)
    }

    private companion object {
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

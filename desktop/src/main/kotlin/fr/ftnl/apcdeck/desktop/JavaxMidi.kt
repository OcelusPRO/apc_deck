package fr.ftnl.apcdeck.desktop

import fr.ftnl.apcdeck.core.device.MidiBackend
import fr.ftnl.apcdeck.core.device.MidiConnection
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage

/**
 * APC via javax.sound.midi.
 * Ports Windows : "APC Key 25 mk2" (clavier, sustain, SysEx) et "MIDIIN2/MIDIOUT2 (APC Key 25 mk2)"
 * (pads, boutons, potars en entrée ; LED en sortie).
 */
object JavaxMidi : MidiBackend {
    override fun open(onMessage: (ByteArray, Boolean) -> Unit): MidiConnection {
        val devices = MidiSystem.getMidiDeviceInfo()
            .filter { it.name.contains("APC", ignoreCase = true) }
            .map { MidiSystem.getMidiDevice(it) }
        val inputs = devices.filter { it.maxTransmitters != 0 }
        val outputs = devices.filter { it.maxReceivers != 0 }
        val (inMain, inControl) = split(inputs, "MIDIIN2")
        val (outMain, outControl) = split(outputs, "MIDIOUT2")
        requireNotNull(inMain ?: inControl) { "APC Key 25 mk2 introuvable (branché ? utilisé par un autre programme ?)" }
        requireNotNull(outMain ?: outControl) { "port de sortie de l'APC introuvable" }

        val connection = Connection(
            "entrée ${inMain?.deviceInfo?.name} / ${inControl?.deviceInfo?.name}, " +
                "sortie ${outMain?.deviceInfo?.name} / ${outControl?.deviceInfo?.name}",
        )
        try {
            inMain?.let { connection.listen(it, control = false, onMessage) }
            inControl?.let { connection.listen(it, control = true, onMessage) }
            connection.mainOut = outMain?.let(connection::receiverOf)
            connection.controlOut = outControl?.let(connection::receiverOf)
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
        return connection
    }

    private fun split(devices: List<MidiDevice>, controlTag: String): Pair<MidiDevice?, MidiDevice?> {
        val control = devices.firstOrNull { controlTag in it.deviceInfo.name }
        val main = devices.firstOrNull { it !== control }
        return if (control == null && devices.size > 1) devices[0] to devices[1] else main to control
    }

    private class Connection(override val description: String) : MidiConnection {
        private val opened = mutableListOf<MidiDevice>()
        var mainOut: Receiver? = null
        var controlOut: Receiver? = null

        fun listen(device: MidiDevice, control: Boolean, onMessage: (ByteArray, Boolean) -> Unit) {
            device.open()
            opened += device
            device.transmitter.receiver = object : Receiver {
                override fun send(message: MidiMessage, timeStamp: Long) {
                    if (message is ShortMessage) onMessage(message.message.copyOf(message.length), control)
                }

                override fun close() {}
            }
        }

        fun receiverOf(device: MidiDevice): Receiver {
            device.open()
            opened += device
            return device.receiver
        }

        override fun send(message: ByteArray, control: Boolean) {
            val out = (if (control) controlOut ?: mainOut else mainOut ?: controlOut) ?: return
            val m = if ((message[0].toInt() and 0xFF) == 0xF0) SysexMessage(message, message.size)
            else ShortMessage(message[0].toInt() and 0xFF, message.getOrElse(1) { 0 }.toInt(), message.getOrElse(2) { 0 }.toInt())
            out.send(m, -1)
        }

        override fun close() {
            opened.forEach { runCatching { it.close() } }
            opened.clear()
            mainOut = null
            controlOut = null
        }
    }
}

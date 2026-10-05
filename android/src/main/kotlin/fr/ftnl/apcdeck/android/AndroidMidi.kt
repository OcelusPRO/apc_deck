package fr.ftnl.apcdeck.android

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import fr.ftnl.apcdeck.core.device.MidiBackend
import fr.ftnl.apcdeck.core.device.MidiConnection
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * APC branché en USB (câble OTG) via android.media.midi. L'APC Key 25 mk2 expose deux ports dans chaque sens :
 * le premier (clavier, sustain, SysEx) et le second (pads, boutons, potars ; LED), comme MIDIIN2/MIDIOUT2 sous Windows.
 */
class AndroidMidi(context: Context) : MidiBackend {
    private val manager: MidiManager? = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val handler: Handler = Handler(HandlerThread("apc-midi").apply { start() }.looper)

    override fun open(onMessage: (ByteArray, Boolean) -> Unit): MidiConnection {
        val midi = manager ?: error("MIDI indisponible sur cet appareil")
        @Suppress("DEPRECATION") // getDevicesForTransport (API 33) donne la même liste pour l'USB
        val info = midi.devices.firstOrNull { nameOf(it).contains("APC", ignoreCase = true) }
            ?: error("APC Key 25 mk2 introuvable (branché avec un câble OTG ?)")

        val latch = CountDownLatch(1)
        var device: MidiDevice? = null
        midi.openDevice(info, { opened -> device = opened; latch.countDown() }, handler)
        if (!latch.await(5, TimeUnit.SECONDS)) error("l'APC ne répond pas")
        val opened = device ?: error("APC impossible à ouvrir (utilisé par une autre application ?)")

        val connection = Connection(opened, nameOf(info))
        try {
            // Ports de sortie de l'appareil = ce que l'APC envoie ; ports d'entrée = ce qu'on lui envoie.
            val outputs = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.sortedBy { it.portNumber }
            val inputs = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }.sortedBy { it.portNumber }
            require(outputs.isNotEmpty() && inputs.isNotEmpty()) { "ports MIDI de l'APC introuvables" }
            outputs.forEachIndexed { i, port ->
                val control = isControl(port, i, outputs.size)
                val out = opened.openOutputPort(port.portNumber) ?: throw IOException("port ${port.portNumber} indisponible")
                connection.listen(out, control, onMessage)
            }
            inputs.forEachIndexed { i, port ->
                val input = opened.openInputPort(port.portNumber) ?: return@forEachIndexed
                if (isControl(port, i, inputs.size)) connection.controlIn = input else connection.mainIn = input
            }
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
        return connection
    }

    /** Port des pads et LED : nommé « …2 » s'il a un nom, sinon le second. */
    private fun isControl(port: MidiDeviceInfo.PortInfo, index: Int, count: Int): Boolean {
        val name = port.name.orEmpty()
        return if (name.contains("2")) true else if (name.contains("1")) false else count > 1 && index == 1
    }

    private fun nameOf(info: MidiDeviceInfo): String = info.properties.run {
        getString(MidiDeviceInfo.PROPERTY_NAME) ?: getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI"
    }

    private class Connection(private val device: MidiDevice, name: String) : MidiConnection {
        override val description: String = "$name (USB)"
        var mainIn: MidiInputPort? = null
        var controlIn: MidiInputPort? = null
        private val outputs = mutableListOf<MidiOutputPort>()

        fun listen(port: MidiOutputPort, control: Boolean, onMessage: (ByteArray, Boolean) -> Unit) {
            outputs += port
            port.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    split(msg, offset, count) { onMessage(it, control) }
                }
            })
        }

        override fun send(message: ByteArray, control: Boolean) {
            val port = (if (control) controlIn ?: mainIn else mainIn ?: controlIn) ?: return
            port.send(message, 0, message.size)
        }

        override fun close() {
            outputs.forEach { runCatching { it.close() } }
            outputs.clear()
            runCatching { mainIn?.close() }
            runCatching { controlIn?.close() }
            runCatching { device.close() }
        }
    }

    companion object {
        /** Un paquet USB peut contenir plusieurs messages : découpés un par un (statut + données). */
        fun split(bytes: ByteArray, offset: Int, count: Int, emit: (ByteArray) -> Unit) {
            var i = offset
            val end = offset + count
            var running = 0
            while (i < end) {
                val b = bytes[i].toInt() and 0xFF
                when {
                    b == 0xF0 -> { // SysEx : jusqu'à F7 (ignoré par l'APC en entrée)
                        val stop = (i until end).firstOrNull { (bytes[it].toInt() and 0xFF) == 0xF7 } ?: (end - 1)
                        i = stop + 1
                    }
                    b >= 0xF8 -> i++ // temps réel (horloge…)
                    b >= 0x80 -> {
                        running = b
                        val length = dataLength(b)
                        if (i + length < end) emit(bytes.copyOfRange(i, i + 1 + length))
                        i += 1 + length
                    }
                    running != 0 -> { // statut courant
                        val length = dataLength(running)
                        if (i + length <= end) emit(byteArrayOf(running.toByte()) + bytes.copyOfRange(i, i + length))
                        i += length
                    }
                    else -> i++
                }
            }
        }

        private fun dataLength(status: Int): Int = when (status and 0xF0) {
            0xC0, 0xD0 -> 1
            0xF0 -> if (status == 0xF2) 2 else if (status == 0xF1 || status == 0xF3) 1 else 0
            else -> 2
        }
    }
}

package fr.ftnl.apcdeck.synth

import java.io.ByteArrayInputStream
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

/** Note d'un morceau, à [micros] du début ; [velocity] 0 = relâchement. */
class SongEvent(val micros: Long, val key: Int, val velocity: Int)

/**
 * Morceau lu depuis un fichier MIDI (.mid) : les notes de toutes les pistes, triées dans le temps, changements
 * de tempo appliqués. La batterie (canal 10) est ignorée : le synthé la jouerait comme des notes.
 */
class MidiSong(val events: List<SongEvent>, val lengthMicros: Long) {
    val noteCount: Int get() = events.count { it.velocity > 0 }

    companion object {
        private const val DRUM_CHANNEL = 9
        private const val META_TEMPO = 0x51
        private const val DEFAULT_TEMPO = 500_000 // µs par noire : 120 BPM

        /** [transpose] (en demi-tons) est ajouté à chaque note ; celles qui sortent de 0..127 sont ignorées. */
        fun parse(bytes: ByteArray, transpose: Int = 0): MidiSong {
            val sequence = MidiSystem.getSequence(ByteArrayInputStream(bytes))
            val toMicros = tickConverter(sequence)

            class Raw(val tick: Long, val key: Int, val velocity: Int)
            val raw = mutableListOf<Raw>()
            for (track in sequence.tracks) for (i in 0 until track.size()) {
                val event = track[i]
                val m = event.message as? ShortMessage ?: continue
                if (m.channel == DRUM_CHANNEL) continue
                if (m.command != ShortMessage.NOTE_ON && m.command != ShortMessage.NOTE_OFF) continue
                val key = m.data1 + transpose
                if (key !in 0..127) continue
                val velocity = if (m.command == ShortMessage.NOTE_ON) m.data2 else 0
                raw += Raw(event.tick, key, velocity)
            }
            // Au même instant, relâchements d'abord : une note répétée n'est pas coupée par la fin de la précédente.
            raw.sortWith(compareBy({ it.tick }, { it.velocity > 0 }))
            val events = raw.map { SongEvent(toMicros(it.tick), it.key, it.velocity) }
            val length = maxOf(toMicros(sequence.tickLength), events.lastOrNull()?.micros ?: 0L)
            return MidiSong(events, length)
        }

        /** Tick -> microsecondes, d'après la carte des tempos (toutes pistes confondues). */
        private fun tickConverter(sequence: Sequence): (Long) -> Long {
            if (sequence.divisionType != Sequence.PPQ) {
                val ticksPerSecond = sequence.divisionType.toDouble() * sequence.resolution
                return { tick -> (tick * 1_000_000.0 / ticksPerSecond).toLong() }
            }
            val tempos = sequence.tracks.flatMap { track ->
                (0 until track.size()).mapNotNull { i ->
                    val event = track[i]
                    val m = event.message as? MetaMessage
                    if (m == null || m.type != META_TEMPO || m.data.size < 3) null
                    else event.tick to ((m.data[0].toInt() and 0xFF) shl 16 or
                        ((m.data[1].toInt() and 0xFF) shl 8) or (m.data[2].toInt() and 0xFF))
                }
            }.sortedBy { it.first }
            val resolution = sequence.resolution.toDouble()
            return { tick ->
                var micros = 0.0
                var lastTick = 0L
                var tempo = DEFAULT_TEMPO
                for ((at, next) in tempos) {
                    if (at >= tick) break
                    micros += (at - lastTick) * tempo / resolution
                    lastTick = at
                    tempo = next
                }
                (micros + (tick - lastTick) * tempo / resolution).toLong()
            }
        }
    }
}

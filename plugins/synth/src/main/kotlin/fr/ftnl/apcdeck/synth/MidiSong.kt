package fr.ftnl.apcdeck.synth

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
            val file = SmfFile.parse(bytes)
            val toMicros = tickConverter(file)

            class Raw(val tick: Long, val key: Int, val velocity: Int)
            val raw = mutableListOf<Raw>()
            for (track in file.tracks) for (event in track) {
                val command = event.status and 0xF0
                if (event.status >= 0xF0 || (event.status and 0x0F) == DRUM_CHANNEL) continue
                if (command != NOTE_ON && command != NOTE_OFF) continue
                val key = event.data1 + transpose
                if (key !in 0..127) continue
                val velocity = if (command == NOTE_ON) event.data2 else 0
                raw += Raw(event.tick, key, velocity)
            }
            // Au même instant, relâchements d'abord : une note répétée n'est pas coupée par la fin de la précédente.
            raw.sortWith(compareBy({ it.tick }, { it.velocity > 0 }))
            val events = raw.map { SongEvent(toMicros(it.tick), it.key, it.velocity) }
            val length = maxOf(toMicros(file.tickLength), events.lastOrNull()?.micros ?: 0L)
            return MidiSong(events, length)
        }

        private const val NOTE_OFF = 0x80
        private const val NOTE_ON = 0x90

        /** Tick -> microsecondes, d'après la carte des tempos (toutes pistes confondues). */
        private fun tickConverter(file: SmfFile): (Long) -> Long {
            file.ticksPerSecond?.let { ticksPerSecond -> return { tick -> (tick * 1_000_000.0 / ticksPerSecond).toLong() } }
            val tempos = file.tracks.flatMap { track ->
                track.mapNotNull { e ->
                    val m = e.meta
                    if (e.status != 0xFF || e.data1 != META_TEMPO || m == null || m.size < 3) null
                    else e.tick to ((m[0].toInt() and 0xFF) shl 16 or ((m[1].toInt() and 0xFF) shl 8) or (m[2].toInt() and 0xFF))
                }
            }.sortedBy { it.first }
            val resolution = file.resolution.toDouble()
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

/**
 * Événement d'un fichier MIDI : [status] (0x80..0xEF message de canal, 0xFF méta, 0xF0/0xF7 SysEx) ; pour un méta,
 * [data1] est son type et [meta] ses données.
 */
internal class SmfEvent(val tick: Long, val status: Int, val data1: Int, val data2: Int, val meta: ByteArray? = null)

/**
 * Lecteur de fichiers MIDI standard (formats 0, 1 et 2), en Kotlin pur : javax.sound.midi n'existe pas sur Android.
 * [resolution] : ticks par noire (PPQ) ; en SMPTE, [ticksPerSecond] est renseigné à la place.
 */
internal class SmfFile(val resolution: Int, val ticksPerSecond: Double?, val tracks: List<List<SmfEvent>>) {
    /** Tick du dernier événement, toutes pistes confondues. */
    val tickLength: Long get() = tracks.maxOfOrNull { it.lastOrNull()?.tick ?: 0L } ?: 0L

    private class Reader(val bytes: ByteArray, var pos: Int, val end: Int) {
        fun u8(): Int {
            if (pos >= end) throw IllegalArgumentException("fichier MIDI tronqué")
            return bytes[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        /** Quantité à longueur variable (7 bits par octet). */
        fun vlq(): Long {
            var value = 0L
            repeat(4) {
                val b = u8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if ((b and 0x80) == 0) return value
            }
            throw IllegalArgumentException("longueur variable invalide")
        }

        fun take(n: Int): ByteArray {
            require(n in 0..(end - pos)) { "fichier MIDI tronqué" }
            return bytes.copyOfRange(pos, pos + n).also { pos += n }
        }
    }

    companion object {
        fun parse(bytes: ByteArray): SmfFile {
            val r = Reader(bytes, 0, bytes.size)
            require(bytes.size >= 14 && String(r.take(4), Charsets.US_ASCII) == "MThd") { "ce n'est pas un fichier MIDI" }
            val headerLength = r.u32().toInt()
            require(headerLength >= 6) { "en-tête MIDI invalide" }
            r.u16() // format : 0, 1 ou 2, lus de la même façon
            val trackCount = r.u16()
            val division = r.u16()
            r.pos += headerLength - 6
            val (resolution, ticksPerSecond) = if ((division and 0x8000) == 0) division to null else {
                val fps = when (val frames = -(division shr 8).toByte()) {
                    29 -> 29.97
                    else -> frames.toDouble()
                }
                (division and 0xFF).let { it to fps * it }
            }
            val tracks = mutableListOf<List<SmfEvent>>()
            while (tracks.size < trackCount && r.pos + 8 <= bytes.size) {
                val id = String(r.take(4), Charsets.US_ASCII)
                val length = r.u32().toInt()
                require(length >= 0 && length <= bytes.size - r.pos) { "piste MIDI tronquée" }
                if (id == "MTrk") tracks += readTrack(Reader(bytes, r.pos, r.pos + length))
                r.pos += length // morceau inconnu : ignoré
            }
            return SmfFile(resolution, ticksPerSecond, tracks)
        }

        private fun readTrack(r: Reader): List<SmfEvent> {
            val events = mutableListOf<SmfEvent>()
            var tick = 0L
            var running = 0
            while (r.pos < r.end) {
                tick += r.vlq()
                var status = r.u8()
                when {
                    status == 0xFF -> {
                        val type = r.u8()
                        val data = r.take(r.vlq().toInt())
                        events += SmfEvent(tick, 0xFF, type, 0, data)
                        if (type == 0x2F) break // fin de piste
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        r.take(r.vlq().toInt())
                        events += SmfEvent(tick, status, 0, 0)
                    }
                    else -> {
                        val data1: Int
                        if (status < 0x80) { // statut courant : l'octet lu est déjà la première donnée
                            require(running != 0) { "statut courant sans statut précédent" }
                            data1 = status
                            status = running
                        } else {
                            data1 = r.u8()
                            running = status
                        }
                        val command = status and 0xF0
                        val data2 = if (command == 0xC0 || command == 0xD0) 0 else r.u8()
                        events += SmfEvent(tick, status, data1 and 0x7F, data2 and 0x7F)
                    }
                }
            }
            return events
        }
    }
}

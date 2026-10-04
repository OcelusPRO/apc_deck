package fr.ftnl.apcdeck.soundboard

import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Lit un fichier audio (WAV, AIFF, AU) sur sa propre ligne de la carte son, dans un thread dédié : plusieurs sons
 * peuvent jouer en même temps. [onEnd] est appelé depuis ce thread, une seule fois, à la fin du son, après [stop]
 * ou en cas d'erreur (alors non nulle).
 */
class SoundPlayer(private val file: Path, private val volume: Double, private val onEnd: (Throwable?) -> Unit) {
    @Volatile private var stopped = false
    @Volatile private var line: SourceDataLine? = null

    fun start() {
        Thread(::play, "soundboard-${file.fileName}").apply {
            isDaemon = true
            start()
        }
    }

    /** Coupe le son tout de suite (débloque l'écriture en cours). */
    fun stop() {
        stopped = true
        line?.let { runCatching { it.stop(); it.flush() } }
    }

    private fun play() {
        var error: Throwable? = null
        try {
            toPcm16(AudioSystem.getAudioInputStream(file.toFile())).use { stream ->
                val format = stream.format
                val out = AudioSystem.getSourceDataLine(format)
                out.open(format, format.frameSize * (format.sampleRate / 10).toInt()) // ~100 ms de tampon
                line = out
                try {
                    if (stopped) return@use
                    out.start()
                    val buffer = ByteArray(format.frameSize * 2048)
                    while (!stopped) {
                        val read = stream.readNBytes(buffer, 0, buffer.size).let { it - it % format.frameSize }
                        if (read <= 0) break
                        applyVolume(buffer, read, volume)
                        out.write(buffer, 0, read) // bloque tant que le tampon est plein : cadence la lecture
                    }
                    if (!stopped) out.drain() // attend la fin réelle du son
                } finally {
                    out.stop()
                    out.close()
                }
            }
        } catch (t: Throwable) {
            error = t
        } finally {
            onEnd(error)
        }
    }

    companion object {
        /** Convertit si besoin en PCM 16 bits signé little-endian (le format que [applyVolume] sait traiter). */
        fun toPcm16(source: AudioInputStream): AudioInputStream {
            val f = source.format
            if (f.encoding == AudioFormat.Encoding.PCM_SIGNED && f.sampleSizeInBits == 16 && !f.isBigEndian) return source
            val target = AudioFormat(f.sampleRate, 16, f.channels, true, false)
            return AudioSystem.getAudioInputStream(target, source)
        }

        /** Applique le volume (0..1) à [length] octets de PCM 16 bits little-endian. */
        fun applyVolume(buffer: ByteArray, length: Int, volume: Double) {
            if (volume >= 1.0) return
            var i = 0
            while (i + 1 < length) {
                val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                val scaled = (sample * volume).toInt()
                buffer[i] = (scaled and 0xFF).toByte()
                buffer[i + 1] = (scaled shr 8 and 0xFF).toByte()
                i += 2
            }
        }
    }
}

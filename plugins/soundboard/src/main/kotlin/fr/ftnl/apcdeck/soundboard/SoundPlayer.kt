package fr.ftnl.apcdeck.soundboard

import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lit un fichier audio (WAV, AIFF, AU) sur sa propre ligne de la carte son, dans un thread dédié : plusieurs sons
 * peuvent jouer en même temps. Seule la plage [start]..[end] (secondes, `end` <= 0 = jusqu'à la fin) est jouée,
 * avec un court fondu aux points de coupe. [pause] / [resume] figent la lecture sans perdre la position. [onEnd] est appelé depuis ce thread, une seule fois, à la fin du son,
 * après [stop] ou en cas d'erreur (alors non nulle).
 */
class SoundPlayer(
    private val file: Path,
    gain: Double,
    private val start: Double = 0.0,
    private val end: Double = 0.0,
    private val onEnd: (Throwable?) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var line: SourceDataLine? = null
    private val lock = ReentrantLock()
    private val resumed = lock.newCondition()

    /** En pause : la ligne est arrêtée (son tampon est conservé) et le thread de lecture attend [resume]. */
    @Volatile var paused: Boolean = false
        private set

    /** Gain linéaire (1 = volume d'origine), modifiable pendant la lecture. */
    @Volatile var gain: Double = gain

    fun start() {
        Thread(::play, "soundboard-${file.fileName}").apply {
            isDaemon = true
            start()
        }
    }

    /** Coupe le son tout de suite (débloque l'écriture en cours, et l'attente d'une pause). */
    fun stop() {
        stopped = true
        line?.let { runCatching { it.stop(); it.flush() } }
        lock.withLock { resumed.signalAll() }
    }

    fun pause() {
        lock.withLock {
            if (stopped || paused) return
            paused = true
            line?.let { runCatching { it.stop() } }
        }
    }

    fun resume() {
        lock.withLock {
            if (!paused) return
            paused = false
            line?.let { runCatching { it.start() } }
            resumed.signalAll()
        }
    }

    /** Bloque tant que le son est en pause (et pas arrêté). */
    private fun awaitResume() {
        lock.withLock { while (paused && !stopped) resumed.await() }
    }

    private fun play() {
        var error: Throwable? = null
        try {
            toPcm16(AudioSystem.getAudioInputStream(file.toFile())).use { stream ->
                val format = stream.format
                val rate = format.sampleRate.toDouble()
                val total = stream.frameLength.takeIf { it >= 0 } ?: Long.MAX_VALUE
                val first = (start * rate).toLong().coerceIn(0, total)
                val last = if (end > 0) (end * rate).toLong().coerceIn(first, total) else total
                val fade = (rate * FADE_SECONDS).toLong()
                stream.skipNBytes(first * format.frameSize)

                val out = AudioSystem.getSourceDataLine(format)
                out.open(format, format.frameSize * (format.sampleRate / 10).toInt()) // ~100 ms de tampon
                line = out
                try {
                    if (stopped) return@use
                    lock.withLock { if (!paused) out.start() } // mis en pause avant même de démarrer
                    val buffer = ByteArray(format.frameSize * 2048)
                    var frame = first
                    while (!stopped && frame < last) {
                        awaitResume()
                        val wanted = min(buffer.size.toLong(), (last - frame) * format.frameSize).toInt()
                        val read = stream.readNBytes(buffer, 0, wanted).let { it - it % format.frameSize }
                        if (read <= 0) break
                        val frames = read / format.frameSize
                        // Fondus seulement aux points de coupe (pas au début ni à la fin réels du fichier).
                        val fadeIn = if (first > 0) fade else 0
                        val fadeOut = if (last < total) fade else 0
                        val g = gain
                        applyGain(buffer, frames, format.channels) { i ->
                            val f = frame + i
                            g * min(1.0, min(ramp(f - first, fadeIn), ramp(last - f, fadeOut)))
                        }
                        out.write(buffer, 0, read) // bloque tant que le tampon est plein : cadence la lecture
                        frame += frames
                    }
                    // Attend la fin réelle du son ; une pause pendant cette attente l'interrompt : on reprend après.
                    while (!stopped) {
                        awaitResume()
                        out.drain()
                        if (!paused) break
                    }
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
        const val FADE_SECONDS = 0.005

        private fun ramp(distance: Long, length: Long): Double = if (length <= 0) 1.0 else max(0.0, distance.toDouble() / length)

        /** Convertit si besoin en PCM 16 bits signé little-endian (le format que [applyGain] sait traiter). */
        fun toPcm16(source: AudioInputStream): AudioInputStream {
            val f = source.format
            if (f.encoding == AudioFormat.Encoding.PCM_SIGNED && f.sampleSizeInBits == 16 && !f.isBigEndian) return source
            val target = AudioFormat(f.sampleRate, 16, f.channels, true, false)
            return AudioSystem.getAudioInputStream(target, source)
        }

        /** Applique un gain par image (peut dépasser 1 : saturé aux bornes) à du PCM 16 bits little-endian. */
        inline fun applyGain(buffer: ByteArray, frames: Int, channels: Int, gainOf: (frame: Int) -> Double) {
            for (frame in 0 until frames) {
                val gain = gainOf(frame)
                if (gain == 1.0) continue
                for (c in 0 until channels) {
                    val i = (frame * channels + c) * 2
                    val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                    val scaled = (sample * gain).toInt().coerceIn(-32_768, 32_767)
                    buffer[i] = (scaled and 0xFF).toByte()
                    buffer[i + 1] = (scaled shr 8 and 0xFF).toByte()
                }
            }
        }

        /** Durée du fichier en secondes. */
        fun duration(file: Path): Double = AudioSystem.getAudioFileFormat(file.toFile()).let { it.frameLength / it.format.frameRate.toDouble() }

        /**
         * Écrit dans [target] (WAV) la plage [start]..[end] de [source] (secondes, `end` <= 0 = jusqu'à la fin),
         * avec un court fondu aux points de coupe ; renvoie la durée gardée en secondes.
         */
        fun cut(source: Path, target: Path, start: Double, end: Double): Double =
            toPcm16(AudioSystem.getAudioInputStream(source.toFile())).use { stream ->
                val format = stream.format
                val rate = format.sampleRate.toDouble()
                val total = stream.frameLength
                val first = (start * rate).toLong().coerceIn(0, total)
                val last = if (end > 0) (end * rate).toLong().coerceIn(first, total) else total
                stream.skipNBytes(first * format.frameSize)
                val bytes = stream.readNBytes(((last - first) * format.frameSize).toInt())
                val frames = bytes.size / format.frameSize
                val fade = (rate * FADE_SECONDS).toLong()
                val fadeIn = if (first > 0) fade else 0
                val fadeOut = if (last < total) fade else 0
                applyGain(bytes, frames, format.channels) { i -> min(1.0, min(ramp(i.toLong(), fadeIn), ramp((frames - i).toLong(), fadeOut))) }
                AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()), AudioFileFormat.Type.WAVE, target.toFile())
                frames / rate
            }

        /**
         * Forme d'onde : [count] pics (max |échantillon| sur toutes les voies, 0..1) répartis sur tout le fichier,
         * et sa durée en secondes.
         */
        fun peaks(file: Path, count: Int): Pair<List<Double>, Double> =
            toPcm16(AudioSystem.getAudioInputStream(file.toFile())).use { stream ->
                val format = stream.format
                val frames = stream.frameLength.coerceAtLeast(1)
                val peaks = DoubleArray(count)
                val buffer = ByteArray(format.frameSize * 8192)
                var frame = 0L
                while (true) {
                    val read = stream.readNBytes(buffer, 0, buffer.size)
                    if (read <= 0) break
                    var i = 0
                    while (i + 1 < read) {
                        val sample = abs(((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt())
                        val bucket = ((frame + i / format.frameSize) * count / frames).toInt().coerceIn(0, count - 1)
                        if (sample > peaks[bucket]) peaks[bucket] = sample.toDouble()
                        i += 2
                    }
                    frame += read / format.frameSize
                }
                peaks.map { (it / 32_768.0 * 1000).toInt() / 1000.0 } to frames / format.sampleRate.toDouble()
            }
    }
}

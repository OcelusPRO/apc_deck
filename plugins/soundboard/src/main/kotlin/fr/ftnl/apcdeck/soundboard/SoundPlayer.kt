package fr.ftnl.apcdeck.soundboard

import fr.ftnl.apcdeck.api.Audio
import fr.ftnl.apcdeck.api.AudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lit un fichier audio (WAV, AIFF, AU ; plus MP3, OGG… sur Android) sur sa propre sortie [Audio], dans un thread dédié : plusieurs sons
 * peuvent jouer en même temps. Seule la plage [start]..[end] (secondes, `end` <= 0 = jusqu'à la fin) est jouée,
 * avec un court fondu aux points de coupe. [pause] / [resume] figent la lecture sans perdre la position. [onEnd] est appelé depuis ce thread, une seule fois, à la fin du son,
 * après [stop] ou en cas d'erreur (alors non nulle).
 */
class SoundPlayer(
    private val audio: Audio,
    private val file: Path,
    gain: Double,
    private val start: Double = 0.0,
    private val end: Double = 0.0,
    private val onEnd: (Throwable?) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var line: AudioOutput? = null
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
            audio.openFile(file).use { stream ->
                val frameSize = stream.frameSize
                val rate = stream.sampleRate.toDouble()
                val total = stream.frameLength.takeIf { it >= 0 } ?: Long.MAX_VALUE
                val first = (start * rate).toLong().coerceIn(0, total)
                val last = if (end > 0) (end * rate).toLong().coerceIn(first, total) else total
                val fade = (rate * FADE_SECONDS).toLong()
                stream.skipFrames(first)

                val out = audio.openOutput(stream.sampleRate, stream.channels, stream.sampleRate / 10) // ~100 ms de tampon
                line = out
                try {
                    if (stopped) return@use
                    lock.withLock { if (!paused) out.start() } // mis en pause avant même de démarrer
                    val buffer = ByteArray(frameSize * 2048)
                    var frame = first
                    while (!stopped && frame < last) {
                        awaitResume()
                        val wanted = min(buffer.size.toLong(), (last - frame) * frameSize).toInt()
                        val read = stream.readFully(buffer, 0, wanted).let { it - it % frameSize }
                        if (read <= 0) break
                        val frames = read / frameSize
                        // Fondus seulement aux points de coupe (pas au début ni à la fin réels du fichier).
                        val fadeIn = if (first > 0) fade else 0
                        val fadeOut = if (last < total) fade else 0
                        val g = gain
                        applyGain(buffer, frames, stream.channels) { i ->
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

        /** Durée du fichier en secondes (lu en entier si son format ne la donne pas). */
        fun duration(audio: Audio, file: Path): Double = audio.openFile(file).use { stream ->
            val frames = stream.frameLength.takeIf { it >= 0 } ?: run {
                val buffer = ByteArray(stream.frameSize * 8192)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    total += n / stream.frameSize
                }
                total
            }
            frames / stream.sampleRate.toDouble()
        }

        /**
         * Écrit dans [target] (WAV) la plage [start]..[end] de [source] (secondes, `end` <= 0 = jusqu'à la fin),
         * avec un court fondu aux points de coupe ; renvoie la durée gardée en secondes.
         */
        fun cut(audio: Audio, source: Path, target: Path, start: Double, end: Double): Double =
            audio.openFile(source).use { stream ->
                val frameSize = stream.frameSize
                val rate = stream.sampleRate.toDouble()
                val first = (start * rate).toLong().coerceAtLeast(0)
                stream.skipFrames(first)
                val wanted = if (end > 0) ((end * rate).toLong() - first).coerceAtLeast(0) else Long.MAX_VALUE
                val bytes = readFrames(stream, wanted)
                val frames = bytes.size / frameSize
                val last = first + frames
                // Coupé avant la fin réelle du fichier ? (longueur inconnue : on regarde s'il reste du son)
                val more = if (stream.frameLength >= 0) last < stream.frameLength else stream.read(ByteArray(frameSize), 0, frameSize) > 0
                val fade = (rate * FADE_SECONDS).toLong()
                val fadeIn = if (first > 0) fade else 0
                val fadeOut = if (more) fade else 0
                applyGain(bytes, frames, stream.channels) { i -> min(1.0, min(ramp(i.toLong(), fadeIn), ramp((frames - i).toLong(), fadeOut))) }
                writeWav(target, bytes, stream.sampleRate, stream.channels)
                frames / rate
            }

        /** Lit au plus [maxFrames] images (jusqu'à la fin du fichier). */
        private fun readFrames(stream: fr.ftnl.apcdeck.api.PcmStream, maxFrames: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(stream.frameSize * 8192)
            var remaining = maxFrames
            while (remaining > 0) {
                val n = stream.read(buffer, 0, min(buffer.size.toLong(), remaining * stream.frameSize).toInt())
                if (n <= 0) break
                out.write(buffer, 0, n)
                remaining -= n / stream.frameSize
            }
            return out.toByteArray()
        }

        /** Fichier WAV PCM 16 bits. */
        fun writeWav(target: Path, pcm: ByteArray, sampleRate: Int, channels: Int) {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size); put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(channels.toShort())
                putInt(sampleRate); putInt(sampleRate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size)
            }
            Files.newOutputStream(target).use {
                it.write(header.array())
                it.write(pcm)
            }
        }

        /**
         * Forme d'onde : [count] pics (max |échantillon| sur toutes les voies, 0..1) répartis sur tout le fichier,
         * et sa durée en secondes.
         */
        fun peaks(audio: Audio, file: Path, count: Int): Pair<List<Double>, Double> {
            // Durée inconnue d'avance (certains formats compressés) : une première lecture la mesure.
            val known = audio.openFile(file).use { it.frameLength }
            val frames = (known.takeIf { it >= 0 } ?: (duration(audio, file) * audio.openFile(file).use { it.sampleRate }).toLong())
                .coerceAtLeast(1)
            return audio.openFile(file).use { stream ->
                val frameSize = stream.frameSize
                val peaks = DoubleArray(count)
                val buffer = ByteArray(frameSize * 8192)
                var frame = 0L
                while (true) {
                    val read = stream.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    var i = 0
                    while (i + 1 < read) {
                        val sample = abs(((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt())
                        val bucket = ((frame + i / frameSize) * count / frames).toInt().coerceIn(0, count - 1)
                        if (sample > peaks[bucket]) peaks[bucket] = sample.toDouble()
                        i += 2
                    }
                    frame += read / frameSize
                }
                peaks.map { (it / 32_768.0 * 1000).toInt() / 1000.0 } to frames / stream.sampleRate.toDouble()
            }
        }
    }
}

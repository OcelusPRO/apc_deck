package fr.ftnl.apcdeck.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import fr.ftnl.apcdeck.api.Audio
import fr.ftnl.apcdeck.api.AudioOutput
import fr.ftnl.apcdeck.api.PcmStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/** Son sur Android : AudioTrack (faible latence) en sortie, décodeurs du système pour les fichiers. */
object AndroidAudio : Audio {
    override fun openOutput(sampleRate: Int, channels: Int, bufferFrames: Int): AudioOutput {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "format audio non pris en charge ($sampleRate Hz, $channels voie(s))" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, bufferFrames * channels * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "sortie audio indisponible" }
        return Output(track, sampleRate, channels)
    }

    override fun openFile(file: Path): PcmStream = Decoder(file.toString())

    private class Output(private val track: AudioTrack, override val sampleRate: Int, override val channels: Int) : AudioOutput {
        private var written = 0L

        override fun start() = track.play()
        override fun stop() = track.pause()
        override fun flush() {
            track.flush()
            written = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }

        override fun drain() {
            // Attend que la tête de lecture rattrape ce qui a été écrit (AudioTrack n'a pas de drain bloquant).
            val frames = written
            var last = -1L
            var still = 0
            while (true) {
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head >= frames || track.playState != AudioTrack.PLAYSTATE_PLAYING) return
                if (head == last && ++still > 50) return // bloqué : on n'attend pas indéfiniment
                if (head != last) still = 0
                last = head
                Thread.sleep(10)
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            var done = 0
            while (done < length) {
                val n = track.write(buffer, offset + done, length - done)
                if (n < 0) throw IOException("écriture audio impossible ($n)")
                if (n == 0 && track.playState != AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(5) // en pause : tampon plein
                done += n
            }
            written += length / (channels * 2)
        }

        override fun close() {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    /** Décodage à la volée vers du PCM 16 bits. */
    private class Decoder(path: String) : PcmStream {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        override val sampleRate: Int
        override val channels: Int
        override val frameLength: Long
        private var pending: ByteArray = ByteArray(0)
        private var pendingPos = 0
        private var inputDone = false
        private var outputDone = false
        private var floatOutput = false

        init {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run { extractor.release(); throw IOException("aucune piste audio dans ce fichier") }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            frameLength = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) * sampleRate / 1_000_000 else -1
            codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (t: Throwable) {
                extractor.release()
                throw IOException("format audio non reconnu : ${t.message}")
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val wanted = length - length % frameSize
            while (pendingPos >= pending.size) {
                if (outputDone) return -1
                decodeStep()
            }
            val n = minOf(wanted, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, buffer, offset, n)
            pendingPos += n
            return n
        }

        override fun skipFrames(frames: Long) {
            var remaining = frames * frameSize
            val scratch = ByteArray(frameSize * 4096)
            while (remaining > 0) {
                val n = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                if (n <= 0) return
                remaining -= n
            }
        }

        /** Donne une trame compressée au décodeur et récupère du PCM s'il y en a. */
        private fun decodeStep() {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val info = MediaCodec.BufferInfo()
            val index = codec.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    floatOutput = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)!!
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    pending = toPcm16(out)
                    pendingPos = 0
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        }

        private fun toPcm16(buffer: ByteBuffer): ByteArray {
            if (!floatOutput) return ByteArray(buffer.remaining()).also(buffer::get)
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val out = ByteArray(floats.remaining() * 2)
            for (i in 0 until floats.remaining()) {
                val s = (floats.get(i) * 32_767f).toInt().coerceIn(-32_768, 32_767)
                out[2 * i] = s.toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
            return out
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }
}

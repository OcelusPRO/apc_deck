package fr.ftnl.apcdeck.desktop

import fr.ftnl.apcdeck.api.Audio
import fr.ftnl.apcdeck.api.AudioOutput
import fr.ftnl.apcdeck.api.PcmStream
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Son du PC via javax.sound.sampled (WAV, AIFF, AU). */
object JavaSound : Audio {
    override fun openOutput(sampleRate: Int, channels: Int, bufferFrames: Int): AudioOutput {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, bufferFrames * channels * 2)
        return Output(line, sampleRate, channels)
    }

    override fun openFile(file: Path): PcmStream = Stream(toPcm16(AudioSystem.getAudioInputStream(file.toFile())))

    /** Convertit si besoin en PCM 16 bits signé little-endian. */
    private fun toPcm16(source: AudioInputStream): AudioInputStream {
        val f = source.format
        if (f.encoding == AudioFormat.Encoding.PCM_SIGNED && f.sampleSizeInBits == 16 && !f.isBigEndian) return source
        val target = AudioFormat(f.sampleRate, 16, f.channels, true, false)
        return AudioSystem.getAudioInputStream(target, source)
    }

    private class Output(private val line: SourceDataLine, override val sampleRate: Int, override val channels: Int) : AudioOutput {
        override fun start() = line.start()
        override fun stop() = line.stop()
        override fun flush() = line.flush()
        override fun drain() = line.drain()
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            line.write(buffer, offset, length)
        }

        override fun close() {
            line.stop()
            line.flush()
            line.close()
        }
    }

    private class Stream(private val stream: AudioInputStream) : PcmStream {
        override val sampleRate: Int = stream.format.sampleRate.toInt()
        override val channels: Int = stream.format.channels
        override val frameLength: Long = stream.frameLength
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = stream.read(buffer, offset, length - length % frameSize)
            return if (n <= 0) -1 else n
        }

        override fun skipFrames(frames: Long) = stream.skipNBytes(frames * frameSize)
        override fun close() = stream.close()
    }
}

package fr.ftnl.apcdeck.soundboard

import fr.ftnl.apcdeck.api.MiniJson
import fr.ftnl.apcdeck.api.obj
import java.io.ByteArrayInputStream
import java.nio.file.Files
import fr.ftnl.apcdeck.desktop.JavaSound
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundboardTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[2 * i] = (s and 0xFF).toByte(); b[2 * i + 1] = (s shr 8 and 0xFF).toByte() }
    }

    @Test
    fun `aller-retour d'un son et de son pad`() {
        val sound = Sound("s1", "Applaudissements \"fort\"", color = 57, volume = 150, fileName = "clap.mp3", duration = 2.5, start = 0.25, end = 2.0)
        val slot = Slot(Page(4, 7), 7, 4)
        val json = MiniJson.parse(MiniJson.stringify(sound.toJson() + slot.toJson())).obj()
        assertEquals(sound, Sound.fromJson(json, "s1"))
        assertEquals(slot, Slot.fromJson(json))
        assertEquals(slot, Slot.decode(slot.encode()))
    }

    @Test
    fun `mise à jour partielle et valeurs hors bornes`() {
        val previous = Sound("s1", "Gong", color = 9, volume = 50, fileName = "gong.wav", duration = 4.0, start = 1.0, end = 3.0)
        val updated = Sound.fromJson(mapOf("name" to "  ", "volume" to 250.0, "color" to 0.0), "s1", previous)
        assertEquals(previous.copy(volume = 200, color = 1), updated)
        // Fin avant le début : plage ramenée à « jusqu'à la fin ».
        assertEquals(0.0, Sound.fromJson(mapOf("start" to 2.0, "end" to 1.5), "s1", previous).end)
        assertNull(Slot.fromJson(mapOf("row" to 5.0, "col" to 0.0, "x" to 0.0, "y" to 0.0)))
    }

    @Test
    fun `gain appliqué au PCM 16 bits little-endian, saturé au-delà du volume d origine`() {
        val buffer = pcm(1000, -1000, 32_767, -32_768)
        SoundPlayer.applyGain(buffer, frames = 2, channels = 2) { 0.5 }
        assertContentEquals(pcm(500, -500, 16_383, -16_384), buffer)

        val loud = pcm(1000, 20_000, -20_000)
        SoundPlayer.applyGain(loud, frames = 3, channels = 1) { 2.0 }
        assertContentEquals(pcm(2000, 32_767, -32_768), loud)
    }

    @Test
    fun `forme d'onde d'un fichier WAV`() {
        val format = AudioFormat(4f, 16, 1, true, false)
        val samples = pcm(0, 16_384, 0, 0, -32_768, 0, 0, 0)
        val file = Files.createTempFile("soundboard", ".wav")
        try {
            AudioSystem.write(AudioInputStream(ByteArrayInputStream(samples), format, 8), AudioFileFormat.Type.WAVE, file.toFile())
            val (peaks, duration) = SoundPlayer.peaks(JavaSound, file, 4)
            assertEquals(listOf(0.5, 0.0, 1.0, 0.0), peaks)
            assertEquals(2.0, duration)
            assertEquals(2.0, SoundPlayer.duration(JavaSound, file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `coupe d'un fichier WAV, seule la plage gardée reste`() {
        val format = AudioFormat(1000f, 16, 1, true, false)
        val samples = pcm(*IntArray(1000) { 10_000 }) // 1 s
        val source = Files.createTempFile("soundboard", ".wav")
        val target = Files.createTempFile("soundboard-cut", ".wav")
        try {
            AudioSystem.write(AudioInputStream(ByteArrayInputStream(samples), format, 1000), AudioFileFormat.Type.WAVE, source.toFile())
            assertEquals(0.5, SoundPlayer.cut(JavaSound, source, target, 0.25, 0.75))
            assertEquals(0.5, SoundPlayer.duration(JavaSound, target))
            val kept = AudioSystem.getAudioInputStream(target.toFile()).use { it.readAllBytes() }
            assertEquals(1000, kept.size)
            // Fondu de 5 ms (5 images) aux deux points de coupe, plein volume au milieu.
            assertEquals(0, kept[0].toInt() or kept[1].toInt())
            assertContentEquals(pcm(10_000), kept.copyOfRange(500, 502))
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
        }
    }
}

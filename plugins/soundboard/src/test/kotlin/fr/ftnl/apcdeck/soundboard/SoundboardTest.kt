package fr.ftnl.apcdeck.soundboard

import fr.ftnl.apcdeck.api.MiniJson
import fr.ftnl.apcdeck.api.obj
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundboardTest {
    @Test
    fun `aller-retour d'un son et de son pad`() {
        val sound = Sound("s1", "Applaudissements \"fort\"", color = 57, volume = 80, fileName = "clap.mp3", duration = 2.5)
        val slot = Slot(Page(4, 7), 7, 4)
        val json = MiniJson.parse(MiniJson.stringify(sound.toJson() + slot.toJson())).obj()
        assertEquals(sound, Sound.fromJson(json, "s1"))
        assertEquals(slot, Slot.fromJson(json))
        assertEquals(slot, Slot.decode(slot.encode()))
    }

    @Test
    fun `mise à jour partielle et valeurs hors bornes`() {
        val previous = Sound("s1", "Gong", color = 9, volume = 50, fileName = "gong.wav", duration = 4.0)
        val updated = Sound.fromJson(mapOf("name" to "  ", "volume" to 250.0, "color" to 0.0), "s1", previous)
        assertEquals(previous.copy(volume = 100, color = 1), updated)
        assertNull(Slot.fromJson(mapOf("row" to 5.0, "col" to 0.0, "x" to 0.0, "y" to 0.0)))
    }

    @Test
    fun `volume appliqué au PCM 16 bits little-endian`() {
        fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
            samples.forEachIndexed { i, s -> b[2 * i] = (s and 0xFF).toByte(); b[2 * i + 1] = (s shr 8 and 0xFF).toByte() }
        }
        val buffer = pcm(1000, -1000, 32_767, -32_768)
        SoundPlayer.applyVolume(buffer, buffer.size, 0.5)
        assertContentEquals(pcm(500, -500, 16_383, -16_384), buffer)
    }
}

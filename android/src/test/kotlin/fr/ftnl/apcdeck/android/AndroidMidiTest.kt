package fr.ftnl.apcdeck.android

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidMidiTest {
    private fun split(vararg b: Int): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        val bytes = ByteArray(b.size) { b[it].toByte() }
        AndroidMidi.split(bytes, 0, bytes.size) { m -> out += m.map { it.toInt() and 0xFF } }
        return out
    }

    @Test
    fun `un paquet USB est decoupe message par message`() {
        assertEquals(listOf(listOf(0x90, 1, 127), listOf(0x80, 1, 0)), split(0x90, 1, 127, 0x80, 1, 0))
        assertEquals(listOf(listOf(0x90, 1, 127), listOf(0x90, 2, 0)), split(0x90, 1, 127, 2, 0)) // statut courant
        assertEquals(listOf(listOf(0xB0, 48, 1)), split(0xF8, 0xB0, 48, 1, 0xFE)) // temps réel ignoré
        assertEquals(listOf(listOf(0xC0, 5), listOf(0x90, 3, 3)), split(0xF0, 1, 2, 0xF7, 0xC0, 5, 0x90, 3, 3)) // SysEx sauté
        assertEquals(emptyList(), split(0x90, 1)) // message tronqué
    }
}

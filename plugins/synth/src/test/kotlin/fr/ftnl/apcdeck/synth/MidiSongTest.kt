package fr.ftnl.apcdeck.synth

import java.io.ByteArrayOutputStream
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MidiSongTest {
    private val sr = 44_100

    /** Fichier MIDI : 480 ticks par noire, 120 BPM puis 60 BPM à partir du tick 960. */
    private fun midiFile(): ByteArray {
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        fun tempo(tick: Long, microsPerQuarter: Int) = track.add(MidiEvent(MetaMessage(0x51,
            byteArrayOf((microsPerQuarter shr 16).toByte(), (microsPerQuarter shr 8).toByte(), microsPerQuarter.toByte()), 3), tick))
        fun note(command: Int, channel: Int, key: Int, velocity: Int, tick: Long) =
            track.add(MidiEvent(ShortMessage(command, channel, key, velocity), tick))
        tempo(0, 500_000)
        tempo(960, 1_000_000)
        note(ShortMessage.NOTE_ON, 0, 60, 100, 0)
        note(ShortMessage.NOTE_OFF, 0, 60, 0, 480)
        note(ShortMessage.NOTE_ON, 0, 64, 90, 960)
        note(ShortMessage.NOTE_ON, 0, 64, 0, 1440) // note-on de vélocité 0 = relâchement
        note(ShortMessage.NOTE_ON, 9, 36, 100, 0)  // batterie : ignorée
        return ByteArrayOutputStream().also { MidiSystem.write(sequence, 0, it) }.toByteArray()
    }

    @Test
    fun `les notes sont placees selon la carte des tempos, batterie ignoree`() {
        val song = MidiSong.parse(midiFile(), transpose = -12)
        assertEquals(listOf(0L to 48, 500_000L to 48, 1_000_000L to 52, 2_000_000L to 52),
            song.events.map { it.micros to it.key })
        assertEquals(listOf(100, 0, 90, 0), song.events.map { it.velocity })
        assertEquals(2, song.noteCount)
        assertEquals(2_000_000L, song.lengthMicros)
    }

    private fun render(e: SynthEngine, seconds: Double) {
        val block = FloatArray(256)
        repeat((seconds * sr / 256).toInt()) { e.render(block, 256) }
    }

    private val song = MidiSong(listOf(SongEvent(0, 60, 100), SongEvent(100_000, 60, 0)), 200_000)
    private val short = SynthParams(attack = 0.0, release = 0.0)

    @Test
    fun `le moteur joue le morceau puis s'arrete`() {
        val e = SynthEngine(sr).also { it.params = short }
        e.playSong(2, song)
        assertEquals(setOf(2), e.playingSongs)
        render(e, 0.05)
        assertEquals(1, e.activeVoices)
        render(e, 0.1)
        assertEquals(0, e.activeVoices) // relâchée à 100 ms
        render(e, 0.1)
        assertTrue(e.playingSongs.isEmpty())
    }

    @Test
    fun `en boucle le morceau reprend au debut`() {
        val e = SynthEngine(sr).also { it.params = short; it.songLoop = true }
        e.playSong(0, song)
        render(e, 0.25)
        assertEquals(setOf(0), e.playingSongs)
        assertEquals(1, e.activeVoices) // deuxième passage : la note rejoue
        e.stopSong(0)
        render(e, 0.05)
        assertEquals(0, e.activeVoices)
        assertTrue(e.playingSongs.isEmpty())
    }
}

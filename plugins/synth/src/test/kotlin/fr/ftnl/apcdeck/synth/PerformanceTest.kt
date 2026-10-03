package fr.ftnl.apcdeck.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerformanceTest {
    private val sr = 44_100

    private fun render(e: SynthEngine, seconds: Double): FloatArray {
        val total = (seconds * sr).toInt()
        val out = FloatArray(total)
        val block = FloatArray(256)
        var pos = 0
        while (pos < total) {
            val n = minOf(256, total - pos)
            e.render(block, n)
            block.copyInto(out, pos, 0, n)
            pos += n
        }
        return out
    }

    // --- logique de jeu (latch, accords) -----------------------------------------------------------

    @Test
    fun `accords dépliés depuis la fondamentale`() {
        val s = NoteState().apply { chord = ChordType.MAJOR }
        s.press(60, 100)
        assertEquals(listOf(60, 64, 67), s.sounding().keys.toList())
        s.chord = ChordType.SEVENTH
        assertEquals(listOf(60, 64, 67, 70), s.sounding().keys.toList())
        s.release(60)
        assertTrue(s.sounding().isEmpty())
    }

    @Test
    fun `latch garde les notes puis remplace au geste suivant`() {
        val s = NoteState().apply { latch = true }
        s.press(60, 100); s.press(64, 100)
        s.release(60); s.release(64)
        assertEquals(listOf(60, 64), s.sounding().keys.toList()) // gardées après relâchement
        s.press(67, 90) // nouveau geste : remplace
        assertEquals(listOf(67), s.sounding().keys.toList())
        s.press(71, 90) // ajouté au geste en cours
        assertEquals(listOf(67, 71), s.sounding().keys.toList())
        s.latch = false
        assertEquals(listOf(67, 71), s.sounding().keys.toList()) // encore tenues physiquement
        s.release(67); s.release(71)
        assertTrue(s.sounding().isEmpty())
    }

    @Test
    fun `valeurs qui défilent`() {
        assertEquals(ArpMode.DOWN, ArpMode.UP.next())
        assertEquals(ArpMode.UP, ArpMode.RANDOM.next())
        assertEquals(ChordType.OFF, ChordType.SUS4.next())
    }

    // --- moteur ------------------------------------------------------------------------------------

    @Test
    fun `arpège au tempo, à l'échantillon près`() {
        val e = SynthEngine(sr).also {
            it.params = SynthParams(attack = 0.0, release = 0.0)
            it.performance = Performance(arp = true, arpRate = ArpRate.EIGHTH, bpm = 120) // un pas = 0,25 s
        }
        e.setArpPool(intArrayOf(60, 64, 67))
        render(e, 2.0)
        assertEquals(8L, e.arpSteps) // 2 s / 0,25 s
        e.setArpPool(IntArray(0))
        render(e, 1.0)
        assertEquals(0, e.activeVoices)
        assertEquals(8L, e.arpSteps)
    }

    @Test
    fun `arpège en triolets et en double-croches`() {
        for ((rate, expected) in listOf(ArpRate.SIXTEENTH to 8L, ArpRate.EIGHTH_TRIPLET to 6L, ArpRate.QUARTER to 2L)) {
            val e = SynthEngine(sr).also { it.performance = Performance(arp = true, arpRate = rate, bpm = 120) }
            e.setArpPool(intArrayOf(60))
            render(e, 1.0)
            assertEquals(expected, e.arpSteps, "$rate")
        }
    }

    @Test
    fun `mono une seule voix et glissando vers la nouvelle note`() {
        val e = SynthEngine(sr).also { it.params = SynthParams(waveform = Waveform.SINE, mono = true, attack = 0.0, sustain = 1.0) }
        e.noteOn(57, 100) // 220 Hz
        render(e, 0.2)
        e.noteOn(69, 100) // 440 Hz, legato
        render(e, 0.02)
        val during = e.firstVoiceFreq
        assertTrue(during > 230 && during < 430, "en plein glissando : $during Hz")
        render(e, 0.5)
        assertEquals(1, e.activeVoices)
        assertTrue(abs(e.firstVoiceFreq - 440.0) < 1, "${e.firstVoiceFreq} Hz")
        e.noteOff(69) // revient à la note encore tenue
        render(e, 0.5)
        assertTrue(abs(e.firstVoiceFreq - 220.0) < 1, "${e.firstVoiceFreq} Hz")
    }

    @Test
    fun `écho prolonge le son et la coupure nette l'efface`() {
        val echo = SynthEngine(sr).also { it.params = SynthParams(echo = true, release = 0.0, attack = 0.0) }
        echo.noteOn(60, 127)
        render(echo, 0.1)
        echo.noteOff(60)
        render(echo, 0.1) // la note est éteinte
        val tail = render(echo, 0.6) // répétitions à 0,375 s (120 BPM)
        assertTrue(tail.any { abs(it) > 0.01f }, "pas de répétition entendue")

        val dry = SynthEngine(sr).also { it.params = SynthParams(echo = false, release = 0.0, attack = 0.0) }
        dry.noteOn(60, 127)
        render(dry, 0.1)
        dry.noteOff(60)
        render(dry, 0.1)
        assertTrue(render(dry, 0.6).all { abs(it) < 1e-6f }, "son sans écho après relâchement")

        echo.noteOn(60, 127)
        render(echo, 0.1)
        echo.panic()
        render(echo, 0.01)
        assertTrue(render(echo, 1.0).all { it == 0f }, "la coupure nette doit aussi vider l'écho")
    }

    @Test
    fun `vibrato fait varier la hauteur sans la décaler en moyenne`() {
        val e = SynthEngine(sr).also { it.params = SynthParams(waveform = Waveform.SINE, vibrato = true, detune = 0.0, cutoff = 1.0, attack = 0.0, sustain = 1.0) }
        e.noteOn(69, 100)
        render(e, 0.1)
        val samples = render(e, 2.0)
        var crossings = 0
        for (i in 1 until samples.size) if (samples[i - 1] < 0 && samples[i] >= 0) crossings++
        assertTrue(abs(crossings / 2.0 - 440.0) < 4.4, "${crossings / 2.0} Hz en moyenne")
    }
}

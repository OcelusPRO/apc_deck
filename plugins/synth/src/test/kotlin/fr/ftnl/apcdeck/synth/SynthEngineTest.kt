package fr.ftnl.apcdeck.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests hors ligne : on appelle render() directement, sans carte son. */
class SynthEngineTest {
    private val sr = 44_100

    private fun engine(params: SynthParams) = SynthEngine(sr).also { it.params = params }

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

    /** Fréquence estimée par passages à zéro montants. */
    private fun frequency(samples: FloatArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) if (samples[i - 1] < 0 && samples[i] >= 0) crossings++
        return crossings * sr.toDouble() / samples.size
    }

    private val pure = SynthParams(waveform = Waveform.SINE, detune = 0.0, cutoff = 1.0, attack = 0.0, sustain = 1.0)

    @Test
    fun `la note MIDI donne la bonne fréquence`() {
        assertEquals(440.0, midiToHz(69.0), 1e-9)
        assertEquals(261.63, midiToHz(60.0), 0.01)
        for ((note, expected) in listOf(69 to 440.0, 60 to 261.63, 81 to 880.0, 45 to 110.0)) {
            val e = engine(pure)
            e.noteOn(note, 100)
            render(e, 0.1) // attaque
            val measured = frequency(render(e, 1.0))
            assertTrue(abs(measured - expected) / expected < 0.01, "note $note : $measured Hz au lieu de $expected")
        }
    }

    @Test
    fun `impulsion sans composante continue et à la bonne fréquence`() {
        val e = engine(pure.copy(waveform = Waveform.PULSE))
        e.noteOn(57, 100) // 220 Hz
        render(e, 0.1)
        val samples = render(e, 1.0)
        val mean = samples.average()
        assertTrue(abs(mean) < 0.02, "composante continue : $mean")
        val measured = frequency(samples)
        assertTrue(abs(measured - 220.0) / 220.0 < 0.01, "$measured Hz au lieu de 220")
    }

    @Test
    fun `octave transpose de 12 demi-tons`() {
        val e = SynthEngine(sr, transposeOctaves = 1).also { it.params = pure }
        e.noteOn(69, 100)
        render(e, 0.1)
        val measured = frequency(render(e, 1.0))
        assertTrue(abs(measured - 880.0) < 9, "$measured Hz au lieu de 880")
    }

    @Test
    fun `sans désaccord le son ne s'annule pas`() {
        for (w in Waveform.entries) {
            val e = engine(pure.copy(waveform = w))
            e.noteOn(57, 100)
            render(e, 0.05)
            val peak = render(e, 0.2).maxOf { abs(it) }
            assertTrue(peak > 0.2f, "$w : crête $peak")
        }
    }

    @Test
    fun `relâchement puis silence`() {
        val e = engine(SynthParams(release = 0.2)) // ~ 0.05 s
        e.noteOn(60, 127)
        render(e, 0.3)
        assertTrue(e.activeVoices == 1)
        e.noteOff(60)
        render(e, 1.0)
        assertEquals(0, e.activeVoices)
        assertTrue(render(e, 0.1).all { it == 0f })
    }

    @Test
    fun `seize notes saturées restent bornées et sans NaN`() {
        val e = engine(SynthParams(waveform = Waveform.SAW, volume = 1.0, resonance = 1.0, cutoff = 0.5, detune = 1.0))
        for (n in 40 until 70) e.noteOn(n, 127) // plus de notes que de voix : vol de voix
        val out = render(e, 1.0)
        assertTrue(out.none { it.isNaN() || it.isInfinite() })
        assertTrue(out.all { abs(it) <= 1f })
        assertTrue(out.any { abs(it) > 0.1f })
        assertEquals(16, e.activeVoices)
    }

    @Test
    fun `allNotesOff coupe tout`() {
        val e = engine(SynthParams(release = 0.1))
        listOf(60, 64, 67).forEach { e.noteOn(it, 100) }
        render(e, 0.2)
        e.allNotesOff()
        render(e, 1.0)
        assertEquals(0, e.activeVoices)
    }

    @Test
    fun `tout couper est immédiat même avec un long relâchement`() {
        val e = engine(SynthParams(release = 1.0, sustain = 1.0)) // relâchement normal : ~6 s
        listOf(48, 52, 55, 60).forEach { e.noteOn(it, 127) }
        render(e, 0.3)
        e.panic()
        val tail = render(e, 0.01) // 10 ms
        assertEquals(0, e.activeVoices)
        assertTrue(tail.drop((0.004 * sr).toInt()).all { it == 0f }, "du son reste après 4 ms")
        // Comparaison : allNotesOff laisse le son se prolonger.
        val slow = engine(SynthParams(release = 1.0, sustain = 1.0))
        slow.noteOn(48, 127)
        render(slow, 0.3)
        slow.allNotesOff()
        render(slow, 0.5)
        assertEquals(1, slow.activeVoices)
    }

    @Test
    fun `réglages normalisés et affichage`() {
        val p = SynthParams()
        assertEquals(40.0, p.copy(cutoff = 0.0).cutoffHz, 1e-6)
        assertEquals(18_000.0, p.copy(cutoff = 1.0).cutoffHz, 1e-6)
        assertEquals(1.0, p.with("volume", 3.0).volume)
        assertEquals("70 %", p.display("volume"))
    }
}

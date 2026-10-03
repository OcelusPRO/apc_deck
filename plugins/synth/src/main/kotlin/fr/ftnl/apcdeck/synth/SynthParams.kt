package fr.ftnl.apcdeck.synth

import kotlin.math.pow
import kotlin.math.roundToInt

enum class Waveform(val label: String) {
    SINE("Sinus"), TRIANGLE("Triangle"), SAW("Dent de scie"), SQUARE("Carré"), PULSE("Impulsion");

    companion object {
        fun of(name: String?): Waveform? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Réglages du synthé. Les réglages continus sont normalisés 0..1 (pratique pour les potars et les curseurs) ;
 * les conversions vers les unités réelles sont ici, partagées par le moteur et l'affichage.
 */
data class SynthParams(
    val waveform: Waveform = Waveform.SAW,
    val volume: Double = 0.7,
    val cutoff: Double = 0.7,
    val resonance: Double = 0.2,
    val attack: Double = 0.1,
    val decay: Double = 0.4,
    val sustain: Double = 0.7,
    val release: Double = 0.35,
    val detune: Double = 0.15,
    /** Une seule note à la fois, qui glisse vers la suivante. */
    val mono: Boolean = false,
    /** Écho (répétitions au tempo : croche pointée). */
    val echo: Boolean = false,
    /** Légère oscillation de hauteur. */
    val vibrato: Boolean = false,
) {
    val cutoffHz: Double get() = 40.0 * (18_000.0 / 40.0).pow(cutoff)
    val attackSec: Double get() = 0.001 + 3.0 * attack.pow(3)
    val decaySec: Double get() = 0.005 + 4.0 * decay.pow(3)
    val releaseSec: Double get() = 0.005 + 6.0 * release.pow(3)
    val detuneCents: Double get() = 30.0 * detune

    fun get(name: String): Double = when (name) {
        "volume" -> volume
        "cutoff" -> cutoff
        "resonance" -> resonance
        "attack" -> attack
        "decay" -> decay
        "sustain" -> sustain
        "release" -> release
        "detune" -> detune
        else -> error("réglage inconnu : $name")
    }

    /** Copie avec un réglage continu modifié (borné à 0..1). */
    fun with(name: String, value: Double): SynthParams {
        val v = value.coerceIn(0.0, 1.0)
        return when (name) {
            "volume" -> copy(volume = v)
            "cutoff" -> copy(cutoff = v)
            "resonance" -> copy(resonance = v)
            "attack" -> copy(attack = v)
            "decay" -> copy(decay = v)
            "sustain" -> copy(sustain = v)
            "release" -> copy(release = v)
            "detune" -> copy(detune = v)
            else -> error("réglage inconnu : $name")
        }
    }

    /** Valeur lisible d'un réglage continu. */
    fun display(name: String): String = when (name) {
        "volume", "resonance", "sustain" -> "${(get(name) * 100).roundToInt()} %"
        "cutoff" -> cutoffHz.let { if (it >= 1000) "%.1f kHz".format(it / 1000) else "${it.roundToInt()} Hz" }
        "attack" -> seconds(attackSec)
        "decay" -> seconds(decaySec)
        "release" -> seconds(releaseSec)
        "detune" -> "%.1f cents".format(detuneCents)
        else -> ""
    }

    private fun seconds(s: Double) = if (s < 1) "${(s * 1000).roundToInt()} ms" else "%.2f s".format(s)

    companion object {
        /** Réglages continus, dans l'ordre des 8 potars de l'APC. */
        val KNOBS = listOf("volume", "cutoff", "resonance", "attack", "decay", "sustain", "release", "detune")
        val LABELS = mapOf(
            "volume" to "Volume", "cutoff" to "Filtre (coupure)", "resonance" to "Résonance", "attack" to "Attaque",
            "decay" to "Déclin", "sustain" to "Maintien", "release" to "Relâchement", "detune" to "Désaccord",
        )
    }
}

package fr.ftnl.apcdeck.core.device

import java.io.Closeable

/**
 * Accès MIDI de la plateforme (javax.sound.midi sur PC, android.media.midi sur Android).
 * L'APC Key 25 mk2 expose deux ports : principal (clavier, sustain, SysEx) et contrôle (pads, boutons, potars en
 * entrée ; LED en sortie).
 */
interface MidiBackend {
    /**
     * Ouvre l'APC ; [onMessage] reçoit chaque message brut (statut + données) et le port d'où il vient, sur un thread
     * quelconque. Lève une exception si l'APC est introuvable.
     */
    fun open(onMessage: (message: ByteArray, control: Boolean) -> Unit): MidiConnection
}

interface MidiConnection : Closeable {
    /** Description lisible des ports ouverts. */
    val description: String

    /** Envoie un message (court ou SysEx) sur le port de contrôle ou le port principal (l'autre s'il manque). Lève une exception si l'appareil a disparu. */
    fun send(message: ByteArray, control: Boolean)
}

/** Aucun MIDI (tests, plateforme sans MIDI) : l'APC est toujours introuvable. */
object NoMidi : MidiBackend {
    override fun open(onMessage: (ByteArray, Boolean) -> Unit): MidiConnection = error("MIDI indisponible sur cette plateforme")
}

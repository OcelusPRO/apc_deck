package fr.ftnl.apcdeck.api

import java.io.Closeable
import java.nio.file.Path

/**
 * Son fourni par l'application, le même code de plugin tourne partout : carte son de Java sur PC, AudioTrack et
 * décodeurs du système sur Android. Tout le PCM échangé est en 16 bits signé little-endian, voies entrelacées.
 */
interface Audio {
    /**
     * Ouvre une sortie : [bufferFrames] images de tampon (la latence). Lève une exception si aucune sortie n'est
     * disponible. La sortie est arrêtée : appeler [AudioOutput.start].
     */
    fun openOutput(sampleRate: Int, channels: Int, bufferFrames: Int): AudioOutput

    /**
     * Ouvre un fichier audio en lecture (décodé à la volée). Formats : WAV, AIFF, AU partout ; en plus MP3, OGG,
     * FLAC, AAC… sur Android. Lève une exception si le fichier est illisible ou d'un format inconnu.
     */
    fun openFile(file: Path): PcmStream
}

/** Sortie audio PCM 16 bits. À utiliser depuis un seul thread, sauf [stop] et [flush] (appelables de partout). */
interface AudioOutput : Closeable {
    val sampleRate: Int
    val channels: Int

    fun start()

    /** Arrête la lecture en gardant le tampon : [start] reprend où elle en était. */
    fun stop()

    /** Jette le son en attente dans le tampon. */
    fun flush()

    /** Bloque jusqu'à ce que le tampon ait été joué. */
    fun drain()

    /** Écrit [length] octets (images entières) ; bloque tant que le tampon est plein, ce qui cadence le rendu. */
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

/** Fichier audio décodé en PCM 16 bits, lu séquentiellement. */
interface PcmStream : Closeable {
    val sampleRate: Int
    val channels: Int

    /** Nombre d'images du fichier, -1 si inconnu. */
    val frameLength: Long

    /** Taille d'une image en octets. */
    val frameSize: Int get() = channels * 2

    /** Lit au plus [length] octets ; renvoie le nombre lu (images entières), -1 à la fin. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Saute [frames] images. */
    fun skipFrames(frames: Long)

    /** Lit jusqu'à remplir [length] octets ou atteindre la fin ; renvoie le nombre lu (0 à la fin). */
    fun readFully(buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = read(buffer, offset + total, length - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}

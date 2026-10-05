package fr.ftnl.apcdeck.synth

import fr.ftnl.apcdeck.api.Audio
import fr.ftnl.apcdeck.api.AudioOutput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/** Fréquence d'une note MIDI (La 440 Hz = note 69). */
fun midiToHz(note: Double): Double = 440.0 * 2.0.pow((note - 69.0) / 12.0)

/**
 * Synthé polyphonique soustractif : 2 oscillateurs désaccordés par voix (PolyBLEP pour dent de scie, carré et
 * impulsion), filtre passe-bas résonant (SVF « TPT »), enveloppe ADSR, mode mono avec glissando, vibrato,
 * arpégiateur et écho calés au tempo à l'échantillon près, lecture de morceaux MIDI (à l'échantillon près aussi),
 * sortie saturée en douceur (tanh).
 *
 * Fils : les commandes arrivent par une file sans verrou (n'importe quel thread) ; [render], les voix,
 * l'arpège et l'écho appartiennent au thread audio (ou au test qui appelle render directement).
 */
class SynthEngine(
    val sampleRate: Int = 44_100,
    polyphony: Int = 16,
    /** Transposition fixe appliquée aux notes reçues (le changement d'octave se fait avec le clavier lui-même). */
    private val transposeOctaves: Int = 0,
) {
    @Volatile
    var params: SynthParams = SynthParams()

    @Volatile
    var performance: Performance = Performance()

    private sealed interface Command {
        data class On(val key: Int, val velocity: Double) : Command
        data class Off(val key: Int) : Command
        data class ArpPool(val keys: IntArray) : Command
        data object AllOff : Command
        class PlaySong(val slot: Int, val id: Long, val playback: SongPlayback) : Command
        data class StopSong(val slot: Int) : Command
        data object StopSongs : Command
        data object Panic : Command
    }

    /** KILL : extinction en quelques millisecondes (arrêt immédiat sans claquement). */
    private enum class Stage { OFF, ATTACK, DECAY, SUSTAIN, RELEASE, KILL }

    private class Voice {
        var key = -1
        var stage = Stage.OFF
        var released = false
        var velocity = 0.0
        var freq = 0.0
        var targetFreq = 0.0 // différent de freq pendant un glissando (mode mono)
        var phase1 = 0.0
        var phase2 = 0.0
        var env = 0.0
        var startedAt = 0L
        var ic1 = 0.0
        var ic2 = 0.0
    }

    /** Morceau en cours de lecture, converti en échantillons. [held] : notes du morceau qui sonnent. */
    private class SongPlayback(val frames: LongArray, val keys: IntArray, val velocities: DoubleArray, val length: Long) {
        var id = 0L
        var index = 0
        var pos = 0L
        val held = HashSet<Int>()
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private val voices = Array(polyphony) { Voice() }
    private var clock = 0L

    // Mono : touches tenues dans l'ordre (la dernière joue ; au relâchement on revient à la précédente).
    private val monoStack = ArrayList<Pair<Int, Double>>()

    // Arpège
    private var arpPool = IntArray(0)
    private var arpPos = -1
    private var arpUp = true
    private var arpKey = -1
    // Échéances en échantillons, en décimal : pas d'arrondi qui dériverait au fil des pas.
    private var arpCountdown = 0.0
    private var arpGateCountdown = -1.0

    /** Nombre de pas d'arpège joués (pour les tests et l'affichage). */
    @Volatile
    var arpSteps = 0L
        private set

    // Vibrato et écho
    private var lfoPhase = 0.0
    private val echoBuffer = FloatArray(sampleRate * 2)
    private var echoPos = 0

    /** Après une coupure nette : échantillons pendant lesquels rien n'entre dans l'écho (extinction des voix). */
    private var echoMuted = 0

    // Morceaux MIDI : emplacement -> lecture (thread audio).
    private val songs = HashMap<Int, SongPlayback>()

    /** Emplacement -> numéro de lecture ; mis à jour dès l'envoi de la commande, retiré par le thread audio à la fin. */
    private val songIds = ConcurrentHashMap<Int, Long>()
    private var nextSongId = 0L

    /** Les morceaux reprennent au début une fois terminés. */
    @Volatile
    var songLoop: Boolean = false

    /** Notes que les morceaux font sonner en ce moment (affichage) ; mises à jour par le thread audio. */
    @Volatile
    var songNotes: Set<Int> = emptySet()
        private set
    private var songNotesChanged = false

    /** Emplacements dont le morceau est en cours de lecture. */
    val playingSongs: Set<Int> get() = songIds.keys.toSet()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var flushRequested = false

    fun noteOn(key: Int, velocity: Int) = commands.add(Command.On(key, (velocity.coerceIn(1, 127)) / 127.0))
    fun noteOff(key: Int) = commands.add(Command.Off(key))
    fun allNotesOff() = commands.add(Command.AllOff)

    /** Notes que l'arpégiateur parcourt (triées) ; vide = arpège à l'arrêt. */
    fun setArpPool(keys: IntArray) = commands.add(Command.ArpPool(keys.sortedArray()))

    /** Joue [song] depuis le début (remplace le morceau de cet emplacement s'il jouait déjà). */
    fun playSong(slot: Int, song: MidiSong) {
        val events = song.events
        val frames = LongArray(events.size) { events[it].micros * sampleRate / 1_000_000 }
        // Au moins un échantillon après la dernière note : la boucle ne peut pas tourner sur place.
        val length = maxOf(song.lengthMicros * sampleRate / 1_000_000, (frames.lastOrNull() ?: 0L) + 1)
        val playback = SongPlayback(frames, IntArray(events.size) { events[it].key },
            DoubleArray(events.size) { events[it].velocity / 127.0 }, length)
        val id = ++nextSongId
        songIds[slot] = id
        commands.add(Command.PlaySong(slot, id, playback))
    }

    fun stopSong(slot: Int) {
        songIds.remove(slot)
        commands.add(Command.StopSong(slot))
    }

    fun stopSongs() {
        songIds.clear()
        commands.add(Command.StopSongs)
    }

    /** Silence immédiat : voix éteintes en ~3 ms, écho vidé, morceaux arrêtés, son déjà en attente dans la carte jeté. */
    fun panic() {
        songIds.clear()
        commands.add(Command.Panic)
        flushRequested = true
    }

    /** Nombre de voix en train de sonner (pour l'affichage). */
    val activeVoices: Int get() = voices.count { it.stage != Stage.OFF }

    /** Fréquence actuelle de la première voix active (pour les tests). */
    internal val firstVoiceFreq: Double get() = voices.firstOrNull { it.stage != Stage.OFF }?.freq ?: 0.0

    // --- sortie audio -------------------------------------------------------------------

    /** Ouvre la sortie audio et démarre le thread audio. Lève une exception si aucune sortie n'est disponible. */
    fun start(audio: Audio, blockFrames: Int = 256, bufferFrames: Int = 1024) {
        check(!running) { "déjà démarré" }
        val line: AudioOutput = audio.openOutput(sampleRate, 2, bufferFrames)
        line.start()
        running = true
        thread = Thread({
            val block = FloatArray(blockFrames)
            val bytes = ByteArray(blockFrames * 4)
            try {
                while (running) {
                    render(block, blockFrames)
                    for (i in 0 until blockFrames) {
                        val s = (block[i] * 32_767f).toInt().coerceIn(-32_768, 32_767)
                        val lo = (s and 0xFF).toByte()
                        val hi = (s shr 8 and 0xFF).toByte()
                        bytes[4 * i] = lo; bytes[4 * i + 1] = hi   // gauche
                        bytes[4 * i + 2] = lo; bytes[4 * i + 3] = hi // droite
                    }
                    if (flushRequested) {
                        flushRequested = false
                        line.flush() // jette le son déjà calculé mais pas encore joué
                    }
                    line.write(bytes, 0, bytes.size) // bloque tant que le tampon est plein : cadence le rendu
                }
            } finally {
                line.close()
            }
        }, "synth-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    // --- rendu ------------------------------------------------------------------------------

    /** Calcule [frames] échantillons mono (-1..1) dans [out]. Pas d'arpège et notes des morceaux tombent à l'échantillon près. */
    fun render(out: FloatArray, frames: Int) {
        applyCommands()
        var pos = 0
        while (pos < frames) {
            val n = advanceArp(advanceSongs(frames - pos))
            renderChunk(out, pos, n)
            songs.values.forEach { it.pos += n }
            pos += n
        }
        if (songNotesChanged) {
            songNotesChanged = false
            songNotes = songs.values.flatMapTo(HashSet()) { it.held }
        }
    }

    private fun renderChunk(out: FloatArray, offset: Int, frames: Int) {
        val p = params
        val sr = sampleRate.toDouble()

        // Filtre SVF (Zavalishin) : coefficients communs à toutes les voix pour ce morceau.
        val g = tan(PI * p.cutoffHz.coerceAtMost(sr * 0.45) / sr)
        val k = 2.0 - 1.9 * p.resonance
        val a1 = 1.0 / (1.0 + g * (g + k))
        val a2 = g * a1
        val a3 = g * a2

        val attackStep = 1.0 / (p.attackSec * sr)
        val decayCoef = exp(-5.0 / (p.decaySec * sr))
        val releaseCoef = exp(-5.0 / (p.releaseSec * sr))
        val killStep = 1.0 / (KILL_SECONDS * sr)
        val glideCoef = 1.0 - exp(-1.0 / (GLIDE_SECONDS * sr))
        val detune = 2.0.pow(p.detuneCents / 2.0 / 1200.0)
        val waveform = p.waveform
        val sustain = p.sustain

        // Vibrato : même oscillation pour toutes les voix, calculée une fois par échantillon.
        val vibrato = DoubleArray(frames)
        val lfoStep = VIBRATO_HZ / sr
        for (i in 0 until frames) {
            vibrato[i] = if (p.vibrato) 2.0.pow(VIBRATO_CENTS / 1200.0 * sin(2 * PI * lfoPhase)) else 1.0
            lfoPhase += lfoStep
            if (lfoPhase >= 1.0) lfoPhase -= 1.0
        }

        out.fill(0f, offset, offset + frames)
        for (v in voices) {
            if (v.stage == Stage.OFF) continue
            for (i in 0 until frames) {
                when (v.stage) {
                    Stage.ATTACK -> {
                        v.env += attackStep
                        if (v.env >= 1.0) { v.env = 1.0; v.stage = Stage.DECAY }
                    }
                    Stage.DECAY -> {
                        v.env = sustain + (v.env - sustain) * decayCoef
                        if (abs(v.env - sustain) < 1e-4) { v.env = sustain; v.stage = Stage.SUSTAIN }
                    }
                    Stage.SUSTAIN -> v.env = sustain
                    Stage.RELEASE -> {
                        v.env *= releaseCoef
                        if (v.env < 1e-4) { v.env = 0.0; v.stage = Stage.OFF; v.key = -1; break }
                    }
                    Stage.KILL -> {
                        v.env -= killStep
                        if (v.env <= 0.0) { v.env = 0.0; v.stage = Stage.OFF; v.key = -1; break }
                    }
                    Stage.OFF -> break
                }
                if (v.freq != v.targetFreq) v.freq += (v.targetFreq - v.freq) * glideCoef
                val f = v.freq * vibrato[i]
                val dt1 = f * detune / sr
                val dt2 = f / detune / sr
                val osc = 0.5 * (oscillator(waveform, v.phase1, dt1) + oscillator(waveform, v.phase2, dt2))
                v.phase1 += dt1; if (v.phase1 >= 1.0) v.phase1 -= 1.0
                v.phase2 += dt2; if (v.phase2 >= 1.0) v.phase2 -= 1.0

                // Passe-bas SVF
                val v3 = osc - v.ic2
                val f1 = a1 * v.ic1 + a2 * v3
                val f2 = v.ic2 + a2 * v.ic1 + a3 * v3
                v.ic1 = 2 * f1 - v.ic1
                v.ic2 = 2 * f2 - v.ic2

                out[offset + i] += (f2 * v.env * v.velocity).toFloat()
            }
        }

        // Écho (croche pointée au tempo) puis saturation douce. Écho coupé : la traîne s'éteint naturellement.
        val gain = p.volume * 0.6
        val delay = (ECHO_BEATS * 60.0 / performance.bpm * sr).toInt().coerceIn(1, echoBuffer.size - 1)
        for (i in 0 until frames) {
            val dry = out[offset + i] * gain
            val delayed = echoBuffer[(echoPos - delay + echoBuffer.size) % echoBuffer.size]
            val feed = p.echo && echoMuted == 0
            if (echoMuted > 0) echoMuted--
            echoBuffer[echoPos] = (if (feed) dry.toFloat() else 0f) + (ECHO_FEEDBACK * delayed).toFloat()
            echoPos = (echoPos + 1) % echoBuffer.size
            out[offset + i] = tanh(dry + ECHO_MIX * delayed).toFloat()
        }
    }

    // --- arpège ----------------------------------------------------------------------------------

    /**
     * Joue les pas d'arpège dus maintenant et renvoie combien d'échantillons rendre avant le prochain événement
     * (pas suivant ou fin de la note en cours), au plus [maxFrames].
     */
    private fun advanceArp(maxFrames: Int): Int {
        val perf = performance
        if (!perf.arp || arpPool.isEmpty()) {
            if (arpKey >= 0) { releaseKey(arpKey); arpKey = -1 }
            arpCountdown = 0.0
            arpGateCountdown = -1.0
            return maxFrames
        }
        if (arpGateCountdown in -0.5..0.5 && arpKey >= 0) {
            releaseKey(arpKey)
            arpKey = -1
            arpGateCountdown = -1.0
        }
        if (arpCountdown < 0.5) {
            if (arpKey >= 0) releaseKey(arpKey)
            arpKey = nextArpKey(perf.arpMode)
            triggerKey(arpKey, ARP_VELOCITY)
            arpSteps++
            val step = (perf.arpRate.beats * 60.0 / perf.bpm * sampleRate).coerceAtLeast(1.0)
            arpCountdown += step // += : l'erreur d'arrondi du pas précédent est rattrapée
            arpGateCountdown = step * ARP_GATE
        }
        var n = min(maxFrames, ceil(arpCountdown - 0.5).toInt().coerceAtLeast(1))
        if (arpGateCountdown > 0) n = min(n, ceil(arpGateCountdown - 0.5).toInt().coerceAtLeast(1))
        arpCountdown -= n
        if (arpGateCountdown > 0) arpGateCountdown -= n
        return n
    }

    private fun nextArpKey(mode: ArpMode): Int {
        val size = arpPool.size
        arpPos = when (mode) {
            ArpMode.UP -> (arpPos + 1).mod(size)
            ArpMode.DOWN -> (if (arpPos < 0) size - 1 else arpPos - 1).mod(size)
            ArpMode.RANDOM -> (0 until size).random()
            ArpMode.UP_DOWN -> {
                if (size == 1) 0 else {
                    var next = if (arpUp) arpPos + 1 else arpPos - 1
                    if (next >= size) { arpUp = false; next = size - 2 }
                    if (next < 0) { arpUp = true; next = 1 }
                    next
                }
            }
        }
        return arpPool[arpPos.coerceIn(0, size - 1)]
    }

    // --- morceaux MIDI ---------------------------------------------------------------------------

    /**
     * Joue les notes des morceaux dues maintenant et renvoie combien d'échantillons rendre avant la prochaine
     * (ou la fin d'un morceau), au plus [maxFrames].
     */
    private fun advanceSongs(maxFrames: Int): Int {
        var n = maxFrames
        val it = songs.entries.iterator()
        while (it.hasNext()) {
            val (slot, s) = it.next()
            var ended = false
            while (true) {
                while (s.index < s.keys.size && s.frames[s.index] <= s.pos) {
                    val key = s.keys[s.index]
                    val velocity = s.velocities[s.index]
                    if (velocity > 0) {
                        triggerKey(key, velocity)
                        s.held += key
                        songNotesChanged = true
                    } else if (s.held.remove(key)) {
                        releaseKey(key)
                        songNotesChanged = true
                    }
                    s.index++
                }
                if (s.index < s.keys.size || s.pos < s.length) break
                s.held.forEach(::releaseKey) // fin du morceau : notes encore tenues relâchées
                s.held.clear()
                songNotesChanged = true
                if (!songLoop) { ended = true; break }
                s.index = 0
                s.pos = 0
            }
            if (ended) {
                it.remove()
                songIds.remove(slot, s.id) // une nouvelle lecture du même emplacement garde son numéro
                continue
            }
            val next = if (s.index < s.keys.size) s.frames[s.index] - s.pos else s.length - s.pos
            n = min(n.toLong(), next.coerceAtLeast(1)).toInt()
        }
        return n
    }

    private fun stopPlayback(s: SongPlayback?) {
        s?.held?.forEach(::releaseKey)
        songNotesChanged = true
    }

    // --- notes ----------------------------------------------------------------------------------

    private fun applyCommands() {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Command.On -> triggerKey(c.key, c.velocity)
                is Command.Off -> releaseKey(c.key)
                is Command.ArpPool -> {
                    arpPool = c.keys
                    if (arpPool.isEmpty() && arpKey >= 0) { releaseKey(arpKey); arpKey = -1 }
                }
                is Command.PlaySong -> {
                    c.playback.id = c.id
                    stopPlayback(songs.put(c.slot, c.playback))
                }
                is Command.StopSong -> stopPlayback(songs.remove(c.slot))
                Command.StopSongs -> {
                    songs.values.forEach(::stopPlayback)
                    songs.clear()
                }
                Command.AllOff -> {
                    monoStack.clear()
                    voices.forEach { it.released = true; if (it.stage != Stage.OFF) it.stage = Stage.RELEASE }
                }
                Command.Panic -> {
                    monoStack.clear()
                    arpPool = IntArray(0)
                    arpKey = -1
                    songs.clear()
                    songNotesChanged = true
                    echoBuffer.fill(0f)
                    echoMuted = (KILL_SECONDS * 2 * sampleRate).toInt() // les voix qui s'éteignent n'entrent pas dans l'écho
                    voices.forEach { it.released = true; if (it.stage != Stage.OFF) it.stage = Stage.KILL }
                }
            }
        }
    }

    private fun freqOf(key: Int) = midiToHz((key + 12 * transposeOctaves).toDouble())

    private fun triggerKey(key: Int, velocity: Double) = if (params.mono) monoOn(key, velocity) else startVoice(key, velocity)

    private fun releaseKey(key: Int) {
        if (params.mono) monoOff(key)
        voices.filter { it.key == key && it.stage != Stage.OFF && !it.released }
            .forEach { it.released = true; it.stage = Stage.RELEASE }
    }

    /** Mono : une seule voix ; une note jouée pendant qu'une autre est tenue glisse vers sa hauteur (legato). */
    private fun monoOn(key: Int, velocity: Double) {
        monoStack.removeAll { it.first == key }
        monoStack += key to velocity
        val v = voices[0]
        voices.drop(1).forEach { if (it.stage != Stage.OFF && !it.released) { it.released = true; it.stage = Stage.RELEASE } }
        if (v.stage != Stage.OFF && !v.released) {
            v.key = key
            v.velocity = velocity
            v.targetFreq = freqOf(key)
        } else {
            val from = if (v.stage != Stage.OFF) v.freq else freqOf(key)
            start(v, key, velocity)
            v.freq = from // glisse depuis la note qui s'éteignait
        }
    }

    private fun monoOff(key: Int) {
        monoStack.removeAll { it.first == key }
        val v = voices[0]
        if (v.key != key || v.released) return
        val previous = monoStack.lastOrNull() ?: return // plus rien de tenu : releaseKey relâche la voix
        v.key = previous.first
        v.targetFreq = freqOf(previous.first)
    }

    private fun startVoice(key: Int, velocity: Double) {
        // Même touche encore tenue : on la redéclenche ; sinon voix libre, puis la plus avancée dans son relâchement, puis la plus ancienne.
        val voice = voices.firstOrNull { it.key == key && !it.released && it.stage != Stage.OFF }
            ?: voices.firstOrNull { it.stage == Stage.OFF }
            ?: voices.filter { it.released }.minByOrNull { it.env }
            ?: voices.minBy { it.startedAt }
        start(voice, key, velocity)
    }

    private fun start(voice: Voice, key: Int, velocity: Double) {
        if (voice.stage == Stage.OFF) {
            // Même phase de départ : un décalage annulerait le son quand le désaccord est à 0.
            voice.phase1 = 0.0
            voice.phase2 = 0.0
            voice.ic1 = 0.0
            voice.ic2 = 0.0
            voice.env = 0.0
        }
        voice.key = key
        voice.released = false
        voice.stage = Stage.ATTACK
        voice.velocity = velocity
        voice.freq = freqOf(key)
        voice.targetFreq = voice.freq
        voice.startedAt = clock++
    }

    private fun oscillator(w: Waveform, t: Double, dt: Double): Double = when (w) {
        Waveform.SINE -> sin(2 * PI * t)
        Waveform.TRIANGLE -> 1.0 - 4.0 * abs(t - 0.5)
        Waveform.SAW -> 2.0 * t - 1.0 - polyBlep(t, dt)
        Waveform.SQUARE -> (if (t < 0.5) 1.0 else -1.0) + polyBlep(t, dt) - polyBlep((t + 0.5) % 1.0, dt)
        // Impulsion à 25 % : haute un quart du temps, timbre nasal ; + 0.5 retire la composante continue.
        Waveform.PULSE -> (if (t < PULSE_WIDTH) 1.0 else -1.0) + polyBlep(t, dt) -
            polyBlep((t + 1.0 - PULSE_WIDTH) % 1.0, dt) + (1.0 - 2.0 * PULSE_WIDTH)
    }

    /** Correction PolyBLEP : adoucit les discontinuités pour limiter le repliement (aliasing). */
    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private companion object {
        /** Durée de l'extinction de « coupure nette » : assez courte pour être instantanée, assez longue pour ne pas claquer. */
        const val KILL_SECONDS = 0.003

        /** Rapport cyclique de l'onde « Impulsion ». */
        const val PULSE_WIDTH = 0.25

        const val GLIDE_SECONDS = 0.08
        const val VIBRATO_HZ = 5.5
        const val VIBRATO_CENTS = 18.0
        const val ECHO_BEATS = 0.75 // croche pointée
        const val ECHO_FEEDBACK = 0.4
        const val ECHO_MIX = 0.35
        const val ARP_GATE = 0.8 // durée d'une note d'arpège, en fraction du pas
        const val ARP_VELOCITY = 100 / 127.0
    }
}

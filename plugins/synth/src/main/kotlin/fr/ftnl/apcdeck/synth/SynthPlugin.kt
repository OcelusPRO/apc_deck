package fr.ftnl.apcdeck.synth

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.ConfigSpec
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.KnobEvent
import fr.ftnl.apcdeck.api.LedState
import fr.ftnl.apcdeck.api.MiniJson
import fr.ftnl.apcdeck.api.PadColor
import fr.ftnl.apcdeck.api.PadEvent
import fr.ftnl.apcdeck.api.bool
import fr.ftnl.apcdeck.api.double
import fr.ftnl.apcdeck.api.int
import fr.ftnl.apcdeck.api.obj
import fr.ftnl.apcdeck.api.str
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Synthé : les touches du clavier jouent la note MIDI correspondante, transposée d'une octave vers le haut
 * (La 440 Hz = note 57). Pour changer d'octave : les boutons Octave du clavier de l'APC.
 *
 * APC au premier plan :
 *   potars 1..8 : volume, coupure, résonance, attaque, déclin, maintien, relâchement, désaccord
 *   rangée 1    : forme d'onde (sinus, triangle, dent de scie, carré, impulsion) ;
 *                 pad 7 : coupure douce (les notes se terminent avec leur relâchement), pad 8 : coupure nette
 *   rangée 2-3  : notes jouées comme un piano (rangée 3 : Do Ré Mi Fa Sol La Si, rangée 2 : les dièses)
 *   rangée 4    : arpège, mode d'arpège, vitesse, latch, accords, mono + glissando, écho, vibrato
 *   rangée 5    : 8 presets (appui = charger, Shift + appui = enregistrer)
 *   ligne rouge : 8 morceaux MIDI chargés depuis la page (appui = lecture / arrêt ; LED allumée = chargé,
 *                 clignotante = en lecture)
 * Ne joue que lorsqu'il est au premier plan (pas d'écoute en arrière-plan).
 */
class SynthPlugin : ApcPlugin() {
    object Settings : ConfigSpec() {
        val velocity = bool("velocity", "Sensible à la vélocité", default = true)
    }

    override val configSpec: ConfigSpec get() = Settings

    // listensInBackground reste à faux : le synthé ne joue que lorsqu'il est au premier plan.

    private val engine = SynthEngine(transposeOctaves = TRANSPOSE_OCTAVES)
    private var params = SynthParams()
    private var perf = Performance()
    private val presets = arrayOfNulls<SynthParams>(PRESET_COUNT)
    private var audioError: String? = null

    private val notes = NoteState()

    private class Song(val name: String, val song: MidiSong)
    private val songs = arrayOfNulls<Song>(SONG_COUNT)

    /** Derniers morceaux en lecture vus par [onTick] (le moteur retire lui-même ceux qui se terminent). */
    private var playingSongs: Set<Int> = emptySet()

    /** Dernières notes jouées par les morceaux vues par [onTick] : affichées avec celles du clavier. */
    private var songNotes: Set<Int> = emptySet()

    private val songDir: Path get() = ctx.data.directory.resolve("midi")

    /** Notes envoyées directement au moteur (hors arpège) -> vélocité. */
    private var playing: Map<Int, Int> = emptyMap()

    /** Notes qui sonnent (accords dépliés, latch compris) : affichage. */
    private var sounding: Map<Int, Int> = emptyMap()

    override fun onLoad() {
        params = loadParams(ctx.data.getString(KEY_PARAMS)) ?: SynthParams()
        perf = loadPerformance()
        ctx.data.getString(KEY_PRESETS)?.let { text ->
            runCatching { MiniJson.parse(text) as List<*> }.getOrNull()?.forEachIndexed { i, p ->
                if (i < PRESET_COUNT && p != null) presets[i] = loadParams(MiniJson.stringify(p))
            }
        }
        loadSongs()
        engine.songLoop = ctx.data.getBoolean(KEY_SONG_LOOP, false)
        notes.latch = perf.latch
        notes.chord = perf.chord
        engine.params = params
        engine.performance = perf
        try {
            engine.start()
            ctx.log.info("sortie audio ouverte (${engine.sampleRate} Hz)")
        } catch (t: Throwable) {
            audioError = t.message ?: t::class.simpleName
            ctx.log.error("aucune sortie audio disponible", t)
        }
    }

    override fun onUnload() {
        engine.allNotesOff()
        engine.stop()
    }

    override fun onActivate() {
        redraw()
        emitState()
    }

    /** Plus aucun relâchement n'arrivera une fois en arrière-plan : on coupe pour éviter les notes bloquées. */
    override fun onDeactivate() {
        releaseAll()
        // isForeground est encore vrai pendant ce hook : la page sera prévenue au tick suivant.
        ctx.scope.launch { emitState() }
    }

    override fun onPause() = releaseAll()

    /** Suit le moteur : notes jouées par les morceaux (pads de notes, clavier de la page) et morceaux terminés. */
    override fun onTick(deltaMillis: Long) {
        val notesNow = engine.songNotes
        if (notesNow != songNotes) {
            songNotes = notesNow
            drawNotes()
            emitNotes()
        }
        val now = engine.playingSongs
        if (now == playingSongs) return
        playingSongs = now
        drawSongs()
        emitState()
    }

    /** Notes à afficher : clavier (accords, latch) + morceaux MIDI. */
    private fun shownNotes(): List<Int> = (sounding.keys + songNotes).sorted()

    private fun emitNotes() = ctx.web.emit("notes", MiniJson.stringify(shownNotes()))

    override fun onEvent(event: ApcEvent) {
        when (event) {
            is KeyEvent -> play(event)
            is ButtonEvent -> if (event.pressed && event.button.group == Button.Group.TRACK) toggleSong(event.button.index)
            is KnobEvent -> SynthParams.KNOBS.getOrNull(event.index)?.let { name ->
                update(params.with(name, params.get(name) + event.delta / 100.0))
            }
            is PadEvent -> if (event.pressed) pad(event.x, event.y)
            else -> {}
        }
    }

    // --- notes ----------------------------------------------------------------------------------

    private fun play(e: KeyEvent) {
        if (e.pressed) notes.press(e.note, if (ctx.config[Settings.velocity]) e.velocity else 100)
        else notes.release(e.note)
        sync()
    }

    /** Aligne le moteur sur les notes qui doivent sonner (direct, ou réserve de l'arpège). */
    private fun sync() {
        val target = notes.sounding()
        if (perf.arp) {
            engine.setArpPool(target.keys.toIntArray())
        } else {
            (playing.keys - target.keys).forEach(engine::noteOff)
            target.forEach { (key, velocity) -> if (key !in playing) engine.noteOn(key, velocity) }
            playing = target
        }
        sounding = target
        drawNotes()
        emitNotes()
    }

    /** Coupure douce : arrête les morceaux et relâche toutes les notes, qui se terminent avec leur relâchement normal. */
    private fun releaseAll() {
        notes.clear()
        engine.stopSongs()
        engine.setArpPool(IntArray(0))
        engine.allNotesOff()
        playing = emptyMap()
        sync()
        songsChanged()
    }

    /** Coupure nette : silence immédiat (écho compris), sans attendre le relâchement. */
    private fun panic() {
        notes.clear()
        engine.panic()
        playing = emptyMap()
        sync()
        songsChanged()
    }

    // --- pads -------------------------------------------------------------------------------------

    private fun pad(x: Int, y: Int) {
        when {
            y == 0 && x < Waveform.entries.size -> update(params.copy(waveform = Waveform.entries[x]))
            y == 0 && x == 6 -> releaseAll()
            y == 0 && x == 7 -> panic()
            y == 3 -> when (x) {
                0 -> setArp(!perf.arp)
                1 -> setPerformance(perf.copy(arpMode = perf.arpMode.next()))
                2 -> setPerformance(perf.copy(arpRate = perf.arpRate.next()))
                3 -> setLatch(!perf.latch)
                4 -> setChord(perf.chord.next())
                5 -> setMono(!params.mono)
                6 -> update(params.copy(echo = !params.echo))
                7 -> update(params.copy(vibrato = !params.vibrato))
            }
            y == 4 -> if (ctx.shift) savePreset(x) else loadPreset(x)
        }
    }

    private fun setArp(on: Boolean) {
        if (on == perf.arp) return
        if (on) {
            playing.keys.forEach(engine::noteOff) // les notes directes laissent la place à l'arpège
            playing = emptyMap()
            setPerformance(perf.copy(arp = true))
        } else {
            setPerformance(perf.copy(arp = false))
            engine.setArpPool(IntArray(0))
        }
        sync()
    }

    private fun setLatch(on: Boolean) {
        notes.latch = on
        setPerformance(perf.copy(latch = on))
        sync()
    }

    private fun setChord(chord: ChordType) {
        notes.chord = chord
        setPerformance(perf.copy(chord = chord))
        sync()
    }

    private fun setMono(on: Boolean) {
        // Changement de mode : on repart proprement des notes tenues.
        engine.allNotesOff()
        playing = emptyMap()
        update(params.copy(mono = on))
        sync()
    }

    private fun setPerformance(next: Performance) {
        if (next == perf) return
        perf = next
        engine.performance = next
        ctx.data.putString(KEY_PERFORMANCE, MiniJson.stringify(performanceJson()))
        redraw()
        emitState()
    }

    private fun update(next: SynthParams) {
        if (next == params) return
        params = next
        engine.params = next
        ctx.data.putString(KEY_PARAMS, MiniJson.stringify(paramsJson(params)))
        redraw()
        emitState()
    }

    // --- presets ----------------------------------------------------------------------------------

    private fun loadPreset(slot: Int) {
        val preset = presets.getOrNull(slot) ?: return ctx.log.info("preset ${slot + 1} vide (Shift + pad pour enregistrer)")
        if (preset.mono != params.mono) setMono(preset.mono)
        update(preset)
        ctx.log.info("preset ${slot + 1} chargé")
    }

    private fun savePreset(slot: Int) {
        presets[slot] = params
        persistPresets()
        ctx.log.info("son enregistré dans le preset ${slot + 1}")
        redraw()
        emitState()
    }

    private fun clearPreset(slot: Int) {
        presets[slot] = null
        persistPresets()
        redraw()
        emitState()
    }

    private fun persistPresets() =
        ctx.data.putString(KEY_PRESETS, MiniJson.stringify(presets.map { it?.let(::paramsJson) }))

    // --- morceaux MIDI --------------------------------------------------------------------------

    /** Lecture / arrêt. Comme les touches, ne joue que si le synthé est au premier plan. */
    private fun toggleSong(slot: Int) {
        val song = songs.getOrNull(slot) ?: return
        when {
            slot in engine.playingSongs -> engine.stopSong(slot)
            ctx.isForeground && audioError == null -> engine.playSong(slot, song.song)
            else -> return
        }
        songsChanged()
    }

    private fun songsChanged() {
        playingSongs = engine.playingSongs
        drawSongs()
        emitState()
    }

    private fun songFile(slot: Int): Path = songDir.resolve("slot-${slot + 1}.mid")

    /** Les notes du fichier sont jouées à leur vraie hauteur : on annule la transposition du clavier. */
    private fun parseSong(bytes: ByteArray) = MidiSong.parse(bytes, transpose = -12 * TRANSPOSE_OCTAVES)

    private fun loadSongs() {
        val names = ctx.data.getString(KEY_SONGS)?.let { runCatching { MiniJson.parse(it) as List<*> }.getOrNull() } ?: return
        names.forEachIndexed { slot, name ->
            if (slot >= SONG_COUNT || name !is String) return@forEachIndexed
            runCatching { parseSong(Files.readAllBytes(songFile(slot))) }
                .onSuccess { songs[slot] = Song(name, it) }
                .onFailure { ctx.log.warn("morceau ${slot + 1} ($name) illisible : ${it.message}") }
        }
    }

    private fun persistSongs() = ctx.data.putString(KEY_SONGS, MiniJson.stringify(songs.map { it?.name }))

    private fun saveSong(slot: Int, name: String, base64: String) {
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: error("fichier mal transmis")
        require(bytes.size <= MAX_SONG_BYTES) { "fichier trop gros (${bytes.size / 1024} Ko, maximum ${MAX_SONG_BYTES / 1024} Ko)" }
        val song = runCatching { parseSong(bytes) }.getOrElse { error("« $name » n'est pas un fichier MIDI lisible") }
        require(song.noteCount > 0) { "« $name » ne contient aucune note (la batterie, canal 10, est ignorée)" }
        if (slot in engine.playingSongs) engine.stopSong(slot)
        Files.createDirectories(songDir)
        Files.write(songFile(slot), bytes)
        songs[slot] = Song(name, song)
        persistSongs()
        ctx.log.info("morceau ${slot + 1} : $name (${song.noteCount} notes)")
        songsChanged()
    }

    private fun clearSong(slot: Int) {
        if (slot in engine.playingSongs) engine.stopSong(slot)
        songs[slot] = null
        Files.deleteIfExists(songFile(slot))
        persistSongs()
        songsChanged()
    }

    private fun setSongLoop(on: Boolean) {
        engine.songLoop = on
        ctx.data.putBoolean(KEY_SONG_LOOP, on)
        emitState()
    }

    // --- LED ------------------------------------------------------------------------------------

    private fun redraw() {
        val leds = ctx.leds
        leds.clear()
        Waveform.entries.forEachIndexed { x, w ->
            leds.pad(x, 0, WAVE_COLORS.getValue(w), if (w == params.waveform) Effect.SOLID else Effect.BRIGHTNESS_25)
        }
        leds.pad(6, 0, PadColor.ORANGE, Effect.BRIGHTNESS_50) // coupure douce
        leds.pad(7, 0, PadColor.RED, Effect.BRIGHTNESS_50)    // coupure nette

        // Rangée 4 : modes de jeu (vif = actif, pâle = inactif).
        fun toggle(x: Int, color: PadColor, on: Boolean) = leds.pad(x, 3, color, if (on) Effect.SOLID else Effect.BRIGHTNESS_10)
        toggle(0, PadColor.GREEN, perf.arp)
        toggle(1, ARP_MODE_COLORS.getValue(perf.arpMode), perf.arp)
        toggle(2, ARP_RATE_COLORS.getValue(perf.arpRate), perf.arp)
        toggle(3, PadColor.ORANGE, perf.latch)
        toggle(4, CHORD_COLORS.getValue(perf.chord), perf.chord != ChordType.OFF)
        toggle(5, PadColor.CYAN, params.mono)
        toggle(6, PadColor.MINT, params.echo)
        toggle(7, PadColor.LIME, params.vibrato)

        // Rangée 5 : presets (éteint = vide, couleur de son onde = enregistré, pulsant = son actuel).
        presets.forEachIndexed { x, p ->
            when {
                p == null -> leds.pad(x, 4, PadColor.GRAY, Effect.BRIGHTNESS_10)
                p == params -> leds.pad(x, 4, WAVE_COLORS.getValue(p.waveform), Effect.PULSE)
                else -> leds.pad(x, 4, WAVE_COLORS.getValue(p.waveform), Effect.BRIGHTNESS_50)
            }
        }
        drawNotes()
        drawSongs()
    }

    /** Ligne rouge : éteinte = vide, allumée = morceau chargé, clignotante = en lecture. */
    private fun drawSongs() {
        repeat(SONG_COUNT) { slot ->
            ctx.leds.button(Button.track(slot), when {
                slot in playingSongs -> LedState.BLINK
                songs[slot] != null -> LedState.ON
                else -> LedState.OFF
            })
        }
    }

    /**
     * Notes jouées, disposées comme un piano : rangée 3 = Do Ré Mi Fa Sol La Si, rangée 2 = les dièses
     * au-dessus de la note qui les suit (comme les touches noires, rien au-dessus de Do et de Fa).
     */
    private fun drawNotes() {
        val classes = shownNotes().map { it % 12 }.toSet()
        NOTE_PADS.forEach { (pc, pos) ->
            val (x, y) = pos
            val lit = pc in classes
            ctx.leds.pad(x, y, if (lit) PadColor.YELLOW else if (pc in BLACK_KEYS) PadColor.GRAY else PadColor.WHITE,
                if (lit) Effect.SOLID else Effect.BRIGHTNESS_10)
        }
    }

    // --- interface web --------------------------------------------------------------------------

    override fun onWebCall(action: String, body: String): String? {
        val json = MiniJson.parse(body).obj()
        when (action) {
            "state" -> {}
            "set" -> {
                val name = json.str("name") ?: return null
                val value = json.double("value") ?: return null
                update(params.with(name, value))
            }
            "waveform" -> Waveform.of(json.str("waveform"))?.let { update(params.copy(waveform = it)) }
            "toggle" -> when (json.str("name")) {
                "arp" -> setArp(!perf.arp)
                "latch" -> setLatch(!perf.latch)
                "mono" -> setMono(!params.mono)
                "echo" -> update(params.copy(echo = !params.echo))
                "vibrato" -> update(params.copy(vibrato = !params.vibrato))
            }
            "arpMode" -> ArpMode.entries.firstOrNull { it.name == json.str("value") }?.let { setPerformance(perf.copy(arpMode = it)) }
            "arpRate" -> ArpRate.entries.firstOrNull { it.name == json.str("value") }?.let { setPerformance(perf.copy(arpRate = it)) }
            "chord" -> ChordType.entries.firstOrNull { it.name == json.str("value") }?.let(::setChord)
            "bpm" -> json.int("value")?.let { setPerformance(perf.copy(bpm = it.coerceIn(BPM_RANGE))) }
            "presetLoad" -> json.int("slot")?.takeIf { it in 0 until PRESET_COUNT }?.let(::loadPreset)
            "presetSave" -> json.int("slot")?.takeIf { it in 0 until PRESET_COUNT }?.let(::savePreset)
            "presetClear" -> json.int("slot")?.takeIf { it in 0 until PRESET_COUNT }?.let(::clearPreset)
            // Comme sur l'APC : le clavier de la page ne joue que si le synthé est au premier plan.
            "noteOn" -> json.int("note")?.takeIf { ctx.isForeground }?.let { play(KeyEvent(it.coerceIn(0, 127), true, 100)) }
            "noteOff" -> json.int("note")?.let { play(KeyEvent(it.coerceIn(0, 127), false, 0)) }
            "songLoad" -> {
                val slot = json.int("slot")?.takeIf { it in 0 until SONG_COUNT } ?: return null
                saveSong(slot, json.str("name") ?: "morceau.mid", json.str("data") ?: return null)
            }
            "songPlay" -> json.int("slot")?.takeIf { it in 0 until SONG_COUNT }?.let(::toggleSong)
            "songClear" -> json.int("slot")?.takeIf { it in 0 until SONG_COUNT }?.let(::clearSong)
            "songLoop" -> setSongLoop(!engine.songLoop)
            "activate" -> ctx.host.activate(ctx.id)
            "release" -> releaseAll()
            "panic" -> panic()
            else -> error("action inconnue : $action")
        }
        return MiniJson.stringify(state())
    }

    private fun emitState() = ctx.web.emit("state", MiniJson.stringify(state()))

    private fun state(): Map<String, Any?> = mapOf(
        "foreground" to ctx.isForeground,
        "audio" to (audioError == null),
        "audioError" to audioError,
        "waveform" to params.waveform.name,
        "waveforms" to Waveform.entries.map { mapOf("id" to it.name, "label" to it.label) },
        "transpose" to TRANSPOSE_OCTAVES,
        "knobs" to SynthParams.KNOBS.mapIndexed { i, name ->
            mapOf("name" to name, "label" to SynthParams.LABELS[name], "knob" to i + 1,
                "value" to params.get(name), "display" to params.display(name))
        },
        "toggles" to mapOf("arp" to perf.arp, "latch" to perf.latch, "mono" to params.mono,
            "echo" to params.echo, "vibrato" to params.vibrato),
        "arpMode" to perf.arpMode.name,
        "arpModes" to ArpMode.entries.map { mapOf("id" to it.name, "label" to it.label) },
        "arpRate" to perf.arpRate.name,
        "arpRates" to ArpRate.entries.map { mapOf("id" to it.name, "label" to it.label) },
        "chord" to perf.chord.name,
        "chords" to ChordType.entries.map { mapOf("id" to it.name, "label" to it.label) },
        "bpm" to perf.bpm,
        "bpmRange" to listOf(BPM_RANGE.first, BPM_RANGE.last),
        "presets" to presets.map { p ->
            mapOf("saved" to (p != null), "active" to (p != null && p == params), "waveform" to p?.waveform?.name)
        },
        "notes" to shownNotes(),
        "songs" to songs.mapIndexed { slot, s ->
            mapOf("name" to s?.name, "playing" to (slot in playingSongs),
                "seconds" to s?.let { it.song.lengthMicros / 1_000_000.0 }, "notes" to s?.song?.noteCount)
        },
        "songLoop" to engine.songLoop,
    )

    // --- persistance ------------------------------------------------------------------------------

    private fun paramsJson(p: SynthParams): Map<String, Any?> =
        mapOf("waveform" to p.waveform.name, "mono" to p.mono, "echo" to p.echo, "vibrato" to p.vibrato) +
            SynthParams.KNOBS.associateWith { p.get(it) }

    private fun loadParams(text: String?): SynthParams? {
        val json = text?.let { runCatching { MiniJson.parse(it).obj() }.getOrNull() } ?: return null
        var p = SynthParams(
            waveform = Waveform.of(json.str("waveform")) ?: Waveform.SAW,
            mono = json.bool("mono") ?: false,
            echo = json.bool("echo") ?: false,
            vibrato = json.bool("vibrato") ?: false,
        )
        SynthParams.KNOBS.forEach { name -> json.double(name)?.let { p = p.with(name, it) } }
        return p
    }

    private fun performanceJson(): Map<String, Any?> = mapOf(
        "arp" to perf.arp, "arpMode" to perf.arpMode.name, "arpRate" to perf.arpRate.name,
        "latch" to perf.latch, "chord" to perf.chord.name, "bpm" to perf.bpm,
    )

    private fun loadPerformance(): Performance {
        val json = ctx.data.getString(KEY_PERFORMANCE)?.let { runCatching { MiniJson.parse(it).obj() }.getOrNull() }
            ?: return Performance()
        return Performance(
            arp = json.bool("arp") ?: false,
            arpMode = ArpMode.entries.firstOrNull { it.name == json.str("arpMode") } ?: ArpMode.UP,
            arpRate = ArpRate.entries.firstOrNull { it.name == json.str("arpRate") } ?: ArpRate.EIGHTH,
            latch = json.bool("latch") ?: false,
            chord = ChordType.entries.firstOrNull { it.name == json.str("chord") } ?: ChordType.OFF,
            bpm = (json.int("bpm") ?: 120).coerceIn(BPM_RANGE),
        )
    }

    private companion object {
        const val KEY_PARAMS = "params"
        const val KEY_PERFORMANCE = "performance"
        const val KEY_PRESETS = "presets"
        const val PRESET_COUNT = 8
        const val KEY_SONGS = "songs"
        const val KEY_SONG_LOOP = "songLoop"
        const val SONG_COUNT = 8
        const val MAX_SONG_BYTES = 2 * 1024 * 1024
        val BPM_RANGE = 40..240

        /** Transposition fixe : une octave au-dessus de la note reçue. */
        const val TRANSPOSE_OCTAVES = 1
        val BLACK_KEYS = setOf(1, 3, 6, 8, 10)

        /** Classe de hauteur (0 = Do) -> pad (x, y). Naturelles sur la rangée 3, dièses juste au-dessus. */
        val NOTE_PADS: Map<Int, Pair<Int, Int>> = mapOf(
            0 to (0 to 2), 2 to (1 to 2), 4 to (2 to 2), 5 to (3 to 2), 7 to (4 to 2), 9 to (5 to 2), 11 to (6 to 2),
            1 to (1 to 1), 3 to (2 to 1), 6 to (4 to 1), 8 to (5 to 1), 10 to (6 to 1),
        )
        val WAVE_COLORS = mapOf(
            Waveform.SINE to PadColor.CYAN, Waveform.TRIANGLE to PadColor.GREEN,
            Waveform.SAW to PadColor.ORANGE, Waveform.SQUARE to PadColor.MAGENTA,
            Waveform.PULSE to PadColor.BLUE,
        )
        val ARP_MODE_COLORS = mapOf(
            ArpMode.UP to PadColor.CYAN, ArpMode.DOWN to PadColor.SKY,
            ArpMode.UP_DOWN to PadColor.MINT, ArpMode.RANDOM to PadColor.PINK,
        )
        val ARP_RATE_COLORS = mapOf(
            ArpRate.QUARTER to PadColor.WHITE, ArpRate.EIGHTH to PadColor.LIME,
            ArpRate.SIXTEENTH to PadColor.GREEN, ArpRate.EIGHTH_TRIPLET to PadColor.PURPLE,
        )
        val CHORD_COLORS = mapOf(
            ChordType.OFF to PadColor.GRAY, ChordType.MAJOR to PadColor.PINK, ChordType.MINOR to PadColor.PURPLE,
            ChordType.SEVENTH to PadColor.MAGENTA, ChordType.SUS4 to PadColor.SKY,
        )
    }
}

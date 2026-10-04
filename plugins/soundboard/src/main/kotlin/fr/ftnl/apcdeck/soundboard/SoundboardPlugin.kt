package fr.ftnl.apcdeck.soundboard

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.ConfigSpec
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.Grid
import fr.ftnl.apcdeck.api.InputBinding
import fr.ftnl.apcdeck.api.InputKind
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import javax.sound.sampled.AudioSystem
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/** Page : [row] = bouton de la colonne verte (0..4), [col] = bouton de la ligne rouge (0..7). */
data class Page(val row: Int, val col: Int) {
    companion object {
        const val ROWS = 5
        const val COLS = 8
    }
}

data class Slot(val page: Page, val x: Int, val y: Int) {
    fun encode(): String = "${page.row}.${page.col}.$x.$y"

    fun toJson(): Map<String, Any?> = mapOf("row" to page.row, "col" to page.col, "x" to x, "y" to y)

    companion object {
        fun decode(text: String): Slot? = text.split('.').mapNotNull(String::toIntOrNull).takeIf { it.size == 4 }
            ?.let { (r, c, x, y) -> of(r, c, x, y) }

        fun fromJson(json: Map<String, Any?>): Slot? {
            return of(json.int("row") ?: return null, json.int("col") ?: return null,
                json.int("x") ?: return null, json.int("y") ?: return null)
        }

        private fun of(r: Int, c: Int, x: Int, y: Int): Slot? =
            Slot(Page(r, c), x, y).takeIf { r in 0 until Page.ROWS && c in 0 until Page.COLS && x in 0 until Grid.COLS && y in 0 until Grid.ROWS }
    }
}

/** Son placé sur un pad ; le fichier audio est `sounds/<id>.wav` dans le dossier de données. */
data class Sound(
    val id: String,
    val name: String,
    /** Index de la palette de l'APC (1..127). */
    val color: Int,
    /** 0..200 % (au-delà de 100 : amplifié, saturé aux crêtes). */
    val volume: Int,
    /** Nom du fichier d'origine, pour l'affichage. */
    val fileName: String,
    /** Durée du fichier en secondes (0 si inconnue). */
    val duration: Double,
    /** Début de la plage jouée, en secondes. */
    val start: Double = 0.0,
    /** Fin de la plage jouée, en secondes ; 0 = jusqu'à la fin du fichier. */
    val end: Double = 0.0,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "color" to color, "volume" to volume, "fileName" to fileName, "duration" to duration,
        "start" to start, "end" to end,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>, id: String, previous: Sound? = null): Sound = Sound(
            id = id,
            name = json.str("name")?.trim()?.ifEmpty { null } ?: previous?.name ?: "Sans nom",
            color = (json.int("color") ?: previous?.color ?: PadColor.BLUE.index).coerceIn(1, 127),
            volume = (json.int("volume") ?: previous?.volume ?: 100).coerceIn(0, MAX_VOLUME),
            fileName = json.str("fileName") ?: previous?.fileName.orEmpty(),
            duration = json.double("duration") ?: previous?.duration ?: 0.0,
        ).let { sound ->
            val start = (json.double("start") ?: previous?.start ?: 0.0).coerceAtLeast(0.0)
            val end = json.double("end") ?: previous?.end ?: 0.0
            sound.copy(start = start, end = if (end > start) end else 0.0)
        }

        const val MAX_VOLUME = 200
    }
}

/**
 * Soundboard : un son par pad, sur 40 pages (colonne verte × ligne rouge, comme le pager). Appui = lecture, le pad
 * oscille (pulse) tant que le son joue ; ensuite appui court = pause (pad clignotant) / reprise, appui long = arrêt.
 * Stop All coupe tous les sons ; un potar règle le volume général. Tout se règle depuis l'interface web (plage jouée, volume…), qui décode les fichiers audio
 * (MP3, OGG, FLAC…) et les envoie en WAV.
 */
class SoundboardPlugin : ApcPlugin() {
    object Settings : ConfigSpec() {
        val masterKnob = binding(
            "masterKnob", "Volume général", default = InputBinding.Knob(0), accepts = setOf(InputKind.KNOB),
            description = "Potar qui règle le volume de tous les sons",
        )
        val longPress = int(
            "longPress", "Appui long (ms)", default = 500, range = 200..2000,
            description = "Durée d'appui sur un pad en lecture ou en pause pour arrêter le son (appui court = pause / reprise)",
        )
    }

    override val configSpec: ConfigSpec get() = Settings

    private val sounds = LinkedHashMap<Slot, Sound>()
    private var page = Page(0, 0)

    /** Volume général, 0..200 %. */
    private var master = 100

    /** Forme d'onde par id de son (calculée à la demande de l'interface). */
    private val peaksCache = HashMap<String, Pair<List<Double>, Double>>()

    /** Sons en cours de lecture, par id. Confiné au thread principal. */
    private val playing = HashMap<String, SoundPlayer>()

    /** Pads tenus (index physique) sur un son en lecture : id du son et minuterie de l'appui long. */
    private val holds = HashMap<Int, Pair<String, Job>>()

    /** Écoute depuis l'éditeur de l'interface : un seul à la fois, sans effet sur les pads. */
    private var preview: SoundPlayer? = null
    private var previewInfo: Map<String, Any?> = emptyMap()
    private var previewVolume = 100

    /** Numéro de l'écoute, incrémenté à chaque lancement (l'interface y recale son curseur). */
    private var previewRun = 0

    private val soundsDir: Path get() = ctx.data.directory.resolve("sounds").createDirectories()
    private val uploadsDir: Path get() = ctx.data.directory.resolve(".uploads").createDirectories()

    private fun fileOf(sound: Sound): Path = soundsDir.resolve("${sound.id}.wav")

    // --- cycle de vie ------------------------------------------------------------

    override fun onLoad() {
        ctx.data.getString(KEY_SOUNDS)?.let { text ->
            runCatching { MiniJson.parse(text) as List<*> }.getOrNull()?.forEach { item ->
                val json = item.obj()
                val id = json.str("id") ?: return@forEach
                val slot = Slot.fromJson(json) ?: return@forEach
                sounds[slot] = Sound.fromJson(json, id)
            }
        }
        ctx.data.getString(KEY_PAGE)?.let(Slot::decode)?.let { page = it.page }
        master = ctx.data.getInt(KEY_MASTER, 100).coerceIn(0, Sound.MAX_VOLUME)
        // Fichiers orphelins (suppression impossible pendant une lecture) et envois interrompus.
        val used = sounds.values.map { "${it.id}.wav" }.toSet()
        soundsDir.listDirectoryEntries().filter { it.name !in used }.forEach { runCatching { it.deleteIfExists() } }
        uploadsDir.listDirectoryEntries().forEach { runCatching { it.deleteIfExists() } }
        ctx.log.info("${sounds.size} son(s)")
    }

    override fun onUnload() {
        playing.values.forEach(SoundPlayer::stop)
        playing.clear()
        preview?.stop()
    }

    override fun onActivate() = redraw()

    /** Le relâchement des pads tenus n'arrivera pas : on oublie ces appuis. */
    override fun onDeactivate() = cancelHolds()

    override fun onPause() = cancelHolds()

    /** Le potar du volume général a pu changer : son nom est affiché dans l'interface. */
    override fun onConfigChanged() {
        ctx.web.emit("state", MiniJson.stringify(state()))
    }

    override fun onEvent(event: ApcEvent) {
        when (event) {
            is PadEvent -> pad(event)
            is ButtonEvent -> if (event.pressed) when (event.button.group) {
                Button.Group.SCENE -> showPage(page.copy(row = event.button.index))
                Button.Group.TRACK -> showPage(page.copy(col = event.button.index))
                else -> if (event.button == Button.STOP_ALL) stopAll()
            }
            is KnobEvent -> if (ctx.config[Settings.masterKnob].matches(event)) setMaster(master + event.delta * 2)
            else -> {}
        }
    }

    // --- lecture -----------------------------------------------------------------------

    /**
     * Son arrêté : lecture dès l'appui. Son en lecture ou en pause : appui court (relâché avant le délai) =
     * pause / reprise, appui long = arrêt, déclenché sans attendre le relâchement.
     */
    private fun pad(event: PadEvent) {
        if (!event.pressed) {
            val (id, timer) = holds.remove(event.index) ?: return
            timer.cancel()
            return togglePause(id)
        }
        val sound = sounds[Slot(page, event.x, event.y)] ?: return
        if (sound.id !in playing) {
            play(sound)
            return changed(save = false)
        }
        holds.remove(event.index)?.second?.cancel()
        holds[event.index] = sound.id to ctx.scope.launch {
            delay(ctx.config[Settings.longPress].toLong())
            holds.remove(event.index)
            stop(sound.id)
            changed(save = false)
        }
    }

    private fun cancelHolds() {
        holds.values.forEach { it.second.cancel() }
        holds.clear()
    }

    /** Interface web : lecture si arrêté, sinon pause / reprise. */
    private fun playOrPause(slot: Slot) {
        val sound = sounds[slot] ?: return
        if (sound.id in playing) togglePause(sound.id) else play(sound)
        changed(save = false)
    }

    private fun togglePause(id: String) {
        val player = playing[id] ?: return
        if (player.paused) player.resume() else player.pause()
        changed(save = false)
    }

    private fun play(sound: Sound) {
        val file = fileOf(sound)
        if (!file.exists()) return ctx.log.warn("« ${sound.name} » : aucun fichier audio")
        lateinit var player: SoundPlayer
        player = SoundPlayer(file, gainOf(sound), sound.start, sound.end) { error -> ctx.scope.launch { finished(sound, player, error) } }
        playing[sound.id] = player
        player.start()
    }

    private fun gainOf(sound: Sound): Double = sound.volume / 100.0 * master / 100.0

    /** Le volume s'applique aussi aux sons déjà en cours de lecture (et à l'écoute de l'éditeur). */
    private fun updateGains() {
        sounds.values.forEach { sound -> playing[sound.id]?.gain = gainOf(sound) }
        preview?.gain = previewVolume / 100.0 * master / 100.0
    }

    private fun setMaster(volume: Int) {
        val next = volume.coerceIn(0, Sound.MAX_VOLUME)
        if (next == master) return
        master = next
        ctx.data.putInt(KEY_MASTER, master)
        updateGains()
        ctx.web.emit("state", MiniJson.stringify(state()))
    }

    private fun stop(id: String) {
        playing.remove(id)?.stop()
    }

    private fun stopAll() {
        if (playing.isEmpty()) return
        playing.values.forEach(SoundPlayer::stop)
        playing.clear()
        changed(save = false)
    }

    private fun finished(sound: Sound, player: SoundPlayer, error: Throwable?) {
        if (error != null) ctx.log.error("« ${sound.name} » : lecture impossible", error)
        // Le son a pu être arrêté puis relancé entre-temps : seul le lecteur courant compte.
        if (playing[sound.id] === player) {
            playing.remove(sound.id)
            changed(save = false)
        }
    }

    // --- écoute depuis l'éditeur ----------------------------------------------------------

    /** Joue [from]..[to] (secondes, `to` <= 0 = jusqu'à la fin) du fichier de [sound], au volume [volume] (brouillon). */
    private fun startPreview(sound: Sound, from: Double, to: Double, volume: Int) {
        val file = fileOf(sound)
        if (!file.exists()) return
        preview?.stop()
        previewVolume = volume.coerceIn(0, Sound.MAX_VOLUME)
        lateinit var player: SoundPlayer
        player = SoundPlayer(file, previewVolume / 100.0 * master / 100.0, from, to) { error ->
            ctx.scope.launch {
                if (error != null) ctx.log.error("« ${sound.name} » : écoute impossible", error)
                if (preview === player) stopPreview()
            }
        }
        preview = player
        previewInfo = mapOf("id" to sound.id, "from" to from, "to" to to, "run" to ++previewRun)
        player.start()
        emitPreview()
    }

    private fun stopPreview() {
        preview?.stop()
        preview = null
        previewInfo = emptyMap()
        emitPreview()
    }

    private fun previewState(): Map<String, Any?> =
        previewInfo + mapOf("playing" to (preview != null), "paused" to (preview?.paused == true))

    private fun emitPreview() = ctx.web.emit("preview", MiniJson.stringify(previewState()))

    // --- LED ---------------------------------------------------------------------------

    private fun redraw() {
        val leds = ctx.leds
        leds.clear()
        sounds.forEach { (slot, sound) ->
            if (slot.page != page) return@forEach
            val effect = when (playing[sound.id]?.paused) {
                null -> Effect.SOLID
                false -> Effect.PULSE_1_8
                true -> Effect.BLINK_1_4
            }
            leds.pad(slot.x, slot.y, PadColor(sound.color), effect)
        }
        repeat(Page.ROWS) { leds.button(Button.scene(it), if (it == page.row) LedState.ON else LedState.OFF) }
        repeat(Page.COLS) { leds.button(Button.track(it), if (it == page.col) LedState.ON else LedState.OFF) }
    }

    private fun showPage(target: Page) {
        page = target
        ctx.data.putString(KEY_PAGE, Slot(page, 0, 0).encode())
        changed(save = false)
    }

    // --- interface web --------------------------------------------------------------

    override fun onWebCall(action: String, body: String): String? {
        val json = MiniJson.parse(body).obj()
        when (action) {
            "state" -> {}
            "page" -> json.int("row")?.let { r -> json.int("col")?.let { c -> showPage(Page(r.coerceIn(0, 4), c.coerceIn(0, 7))) } }
            "save" -> Slot.fromJson(json)?.let { slot -> save(slot, json) }
            "master" -> json.int("volume")?.let(::setMaster)
            "peaks" -> {
                val sound = Slot.fromJson(json)?.let(sounds::get)?.takeIf { fileOf(it).exists() } ?: return null
                val (peaks, duration) = peaksCache.getOrPut(sound.id) {
                    val count = (SoundPlayer.duration(fileOf(sound)) * PEAKS_PER_SECOND).toInt().coerceIn(MIN_PEAKS, MAX_PEAKS)
                    SoundPlayer.peaks(fileOf(sound), count)
                }
                return MiniJson.stringify(mapOf("id" to sound.id, "peaks" to peaks, "duration" to duration))
            }
            "preview" -> {
                val sound = Slot.fromJson(json)?.let(sounds::get) ?: return null
                startPreview(sound, json.double("from") ?: 0.0, json.double("to") ?: 0.0, json.int("volume") ?: sound.volume)
                return MiniJson.stringify(previewState())
            }
            "previewPause" -> {
                preview?.let { if (it.paused) it.resume() else it.pause() }
                emitPreview()
                return MiniJson.stringify(previewState())
            }
            "previewStop" -> {
                stopPreview()
                return MiniJson.stringify(previewState())
            }
            "previewVolume" -> {
                previewVolume = (json.int("volume") ?: previewVolume).coerceIn(0, Sound.MAX_VOLUME)
                updateGains()
                return null
            }
            "delete" -> Slot.fromJson(json)?.let(::delete)
            "play" -> Slot.fromJson(json)?.let(::playOrPause)
            "stop" -> Slot.fromJson(json)?.let(sounds::get)?.let { stop(it.id); changed(save = false) }
            "stopAll" -> stopAll()
            "upload" -> upload(json)
            "move" -> {
                val from = Slot.fromJson(json["from"].obj()) ?: return null
                val to = Slot.fromJson(json["to"].obj()) ?: return null
                if (from != to) {
                    sounds.remove(from)?.let { moving ->
                        sounds.remove(to)?.let { sounds[from] = it }
                        sounds[to] = moving
                        changed()
                    }
                }
            }
            else -> error("action inconnue : $action")
        }
        return MiniJson.stringify(state())
    }

    /**
     * Fichier audio envoyé par morceaux (base64) : { slot…, index, data, last, fileName, duration }. Le premier
     * morceau (index 0) repart d'un fichier vide ; le dernier remplace le fichier du son (créé s'il n'existe pas).
     */
    private fun upload(json: Map<String, Any?>) {
        val slot = Slot.fromJson(json) ?: error("pad invalide")
        val part = uploadsDir.resolve("${slot.encode()}.part")
        val bytes = Base64.getDecoder().decode(json.str("data").orEmpty())
        if (json.int("index") == 0) part.writeBytes(bytes)
        else part.writeBytes(bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        if (json.bool("last") != true) return

        if (runCatching { AudioSystem.getAudioFileFormat(part.toFile()) }.isFailure) {
            part.deleteIfExists()
            error("format audio non reconnu")
        }
        val fileName = json.str("fileName").orEmpty()
        val previous = sounds[slot]
        // Nouvel id (donc nouveau fichier) : l'ancien fichier peut encore être ouvert par une lecture qui s'arrête.
        val sound = Sound.fromJson(
            mapOf(
                "fileName" to fileName, "duration" to json.double("duration"), "start" to 0.0, "end" to 0.0,
                "name" to (previous?.name ?: fileName.substringBeforeLast('.')),
            ),
            newId(), previous,
        )
        part.moveTo(fileOf(sound), StandardCopyOption.REPLACE_EXISTING)
        previous?.let { stop(it.id); deleteFile(it) }
        sounds[slot] = sound
        ctx.log.info("« ${sound.name} » : fichier $fileName")
        changed()
    }

    /**
     * Enregistre les réglages de l'éditeur. Si une plage { start, end } est donnée, le fichier est coupé pour de
     * bon : les parties ignorées sont supprimées (nouveau fichier, nouvel id : l'ancien peut encore être lu).
     */
    private fun save(slot: Slot, json: Map<String, Any?>) {
        val previous = sounds[slot]
        var sound = Sound.fromJson(json, previous?.id ?: newId(), previous)
        if (previous != null && (sound.start > 0 || sound.end > 0) && fileOf(previous).exists()) {
            val cut = sound.copy(id = newId(), start = 0.0, end = 0.0)
            val duration = SoundPlayer.cut(fileOf(previous), fileOf(cut), sound.start, sound.end)
            if (previewInfo["id"] == previous.id) stopPreview()
            stop(previous.id)
            deleteFile(previous)
            sound = cut.copy(duration = duration)
            ctx.log.info("« ${sound.name} » coupé : ${"%.2f".format(duration)} s gardées")
        }
        sounds[slot] = sound
        updateGains()
        changed()
    }

    private fun delete(slot: Slot) {
        val sound = sounds.remove(slot) ?: return
        stop(sound.id)
        deleteFile(sound)
        ctx.log.info("« ${sound.name} » supprimé")
        changed()
    }

    /** Sous Windows, un fichier encore ouvert par une lecture qui s'arrête ne peut pas être supprimé : fait au prochain chargement. */
    private fun deleteFile(sound: Sound) {
        peaksCache.remove(sound.id)
        runCatching { fileOf(sound).deleteIfExists() }
    }

    /** Identifiant unique : les fichiers audio portent cet id, jamais le nom (deux sons peuvent avoir le même nom). */
    private fun newId(): String = generateSequence {
        "s" + System.currentTimeMillis().toString(36) + (0 until 4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
    }.first { id -> sounds.values.none { it.id == id } && !soundsDir.resolve("$id.wav").exists() }

    private fun state(): Map<String, Any?> = mapOf(
        "current" to mapOf("row" to page.row, "col" to page.col),
        "master" to master,
        "masterKnob" to ctx.config[Settings.masterKnob].label,
        "sounds" to sounds.map { (slot, sound) ->
            sound.toJson() + slot.toJson() + mapOf("playing" to (sound.id in playing), "paused" to (playing[sound.id]?.paused == true), "hasFile" to fileOf(sound).exists())
        },
    )

    /** Après chaque changement : sauvegarde éventuelle, LED, et mise à jour des pages web ouvertes. */
    private fun changed(save: Boolean = true) {
        if (save) ctx.data.putString(KEY_SOUNDS, MiniJson.stringify(sounds.map { (slot, sound) -> sound.toJson() + slot.toJson() }))
        redraw()
        ctx.web.emit("state", MiniJson.stringify(state()))
    }

    private companion object {
        const val KEY_SOUNDS = "sounds"
        const val KEY_PAGE = "page"
        const val KEY_MASTER = "master"
        const val PEAKS_PER_SECOND = 50
        const val MIN_PEAKS = 1000
        const val MAX_PEAKS = 30_000
    }
}

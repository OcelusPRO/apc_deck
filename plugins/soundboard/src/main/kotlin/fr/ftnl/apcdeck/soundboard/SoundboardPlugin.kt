package fr.ftnl.apcdeck.soundboard

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.Grid
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
    /** 0..100 %. */
    val volume: Int,
    /** Nom du fichier d'origine, pour l'affichage. */
    val fileName: String,
    /** Durée en secondes (0 si inconnue). */
    val duration: Double,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "color" to color, "volume" to volume, "fileName" to fileName, "duration" to duration,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>, id: String, previous: Sound? = null): Sound = Sound(
            id = id,
            name = json.str("name")?.trim()?.ifEmpty { null } ?: previous?.name ?: "Sans nom",
            color = (json.int("color") ?: previous?.color ?: PadColor.BLUE.index).coerceIn(1, 127),
            volume = (json.int("volume") ?: previous?.volume ?: 100).coerceIn(0, 100),
            fileName = json.str("fileName") ?: previous?.fileName.orEmpty(),
            duration = json.double("duration") ?: previous?.duration ?: 0.0,
        )
    }
}

/**
 * Soundboard : un son par pad, sur 40 pages (colonne verte × ligne rouge, comme le pager). Appui = lecture, le pad
 * oscille (pulse) tant que le son joue ; nouvel appui = arrêt. Stop All coupe tous les sons. Tout se règle depuis
 * l'interface web, qui décode les fichiers audio (MP3, OGG, FLAC…) et les envoie en WAV.
 */
class SoundboardPlugin : ApcPlugin() {
    private val sounds = LinkedHashMap<Slot, Sound>()
    private var page = Page(0, 0)

    /** Sons en cours de lecture, par id. Confiné au thread principal. */
    private val playing = HashMap<String, SoundPlayer>()

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
        // Fichiers orphelins (suppression impossible pendant une lecture) et envois interrompus.
        val used = sounds.values.map { "${it.id}.wav" }.toSet()
        soundsDir.listDirectoryEntries().filter { it.name !in used }.forEach { runCatching { it.deleteIfExists() } }
        uploadsDir.listDirectoryEntries().forEach { runCatching { it.deleteIfExists() } }
        ctx.log.info("${sounds.size} son(s)")
    }

    override fun onUnload() {
        playing.values.forEach(SoundPlayer::stop)
        playing.clear()
    }

    override fun onActivate() = redraw()

    override fun onEvent(event: ApcEvent) {
        when (event) {
            is PadEvent -> if (event.pressed) toggle(Slot(page, event.x, event.y))
            is ButtonEvent -> if (event.pressed) when (event.button.group) {
                Button.Group.SCENE -> showPage(page.copy(row = event.button.index))
                Button.Group.TRACK -> showPage(page.copy(col = event.button.index))
                else -> if (event.button == Button.STOP_ALL) stopAll()
            }
            else -> {}
        }
    }

    // --- lecture -----------------------------------------------------------------------

    private fun toggle(slot: Slot) {
        val sound = sounds[slot] ?: return
        if (sound.id in playing) stop(sound.id) else play(sound)
        changed(save = false)
    }

    private fun play(sound: Sound) {
        val file = fileOf(sound)
        if (!file.exists()) return ctx.log.warn("« ${sound.name} » : aucun fichier audio")
        lateinit var player: SoundPlayer
        player = SoundPlayer(file, sound.volume / 100.0) { error -> ctx.scope.launch { finished(sound, player, error) } }
        playing[sound.id] = player
        player.start()
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

    // --- LED ---------------------------------------------------------------------------

    private fun redraw() {
        val leds = ctx.leds
        leds.clear()
        sounds.forEach { (slot, sound) ->
            if (slot.page != page) return@forEach
            leds.pad(slot.x, slot.y, PadColor(sound.color), if (sound.id in playing) Effect.PULSE_1_8 else Effect.SOLID)
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
            "save" -> Slot.fromJson(json)?.let { slot -> sounds[slot] = Sound.fromJson(json, sounds[slot]?.id ?: newId(), sounds[slot]); changed() }
            "delete" -> Slot.fromJson(json)?.let(::delete)
            "play" -> Slot.fromJson(json)?.let(::toggle)
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
            mapOf("fileName" to fileName, "duration" to json.double("duration"), "name" to (previous?.name ?: fileName.substringBeforeLast('.'))),
            newId(), previous,
        )
        part.moveTo(fileOf(sound), StandardCopyOption.REPLACE_EXISTING)
        previous?.let { stop(it.id); deleteFile(it) }
        sounds[slot] = sound
        ctx.log.info("« ${sound.name} » : fichier $fileName")
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
        runCatching { fileOf(sound).deleteIfExists() }
    }

    /** Identifiant unique : les fichiers audio portent cet id, jamais le nom (deux sons peuvent avoir le même nom). */
    private fun newId(): String = generateSequence {
        "s" + System.currentTimeMillis().toString(36) + (0 until 4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
    }.first { id -> sounds.values.none { it.id == id } && !soundsDir.resolve("$id.wav").exists() }

    private fun state(): Map<String, Any?> = mapOf(
        "current" to mapOf("row" to page.row, "col" to page.col),
        "sounds" to sounds.map { (slot, sound) ->
            sound.toJson() + slot.toJson() + mapOf("playing" to (sound.id in playing), "hasFile" to fileOf(sound).exists())
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
    }
}

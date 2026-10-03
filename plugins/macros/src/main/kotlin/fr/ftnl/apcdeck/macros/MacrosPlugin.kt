package fr.ftnl.apcdeck.macros

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
import fr.ftnl.apcdeck.api.int
import fr.ftnl.apcdeck.api.obj
import fr.ftnl.apcdeck.api.str
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

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

/** Métadonnées d'une macro ; le script lui-même est un fichier du dossier de données. */
data class Macro(
    val id: String,
    val name: String,
    val description: String,
    val shell: Shell,
    val terminal: Boolean,
    /** Index de la palette de l'APC (1..127). */
    val color: Int,
    /** "fixe", "pulse" ou "clignote". */
    val effect: String,
) {
    val padEffect: Effect
        get() = when (effect) {
            "pulse" -> Effect.PULSE
            "clignote" -> Effect.BLINK
            else -> Effect.SOLID
        }

    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "description" to description, "shell" to shell.id,
        "terminal" to terminal, "color" to color, "effect" to effect,
    )

    companion object {
        val EFFECTS = listOf("fixe", "pulse", "clignote")

        fun fromJson(json: Map<String, Any?>, id: String): Macro = Macro(
            id = id,
            name = json.str("name")?.trim()?.ifEmpty { null } ?: "Sans nom",
            description = json.str("description").orEmpty(),
            shell = Shell.of(json.str("shell")),
            terminal = json.bool("terminal") ?: false,
            color = (json.int("color") ?: PadColor.WHITE.index).coerceIn(1, 127),
            effect = json.str("effect")?.takeIf { it in EFFECTS } ?: "fixe",
        )
    }
}

/**
 * Macros : chaque macro est un script shell (PowerShell, cmd ou bash) placé sur un pad d'une des 40 pages
 * (colonne verte × ligne rouge, comme le pager). Appui = lancer ; le pad passe au vert pendant l'exécution ;
 * nouvel appui = arrêt forcé (tout l'arbre de processus). Tout se gère depuis l'interface web.
 */
class MacrosPlugin : ApcPlugin() {
    private val macros = LinkedHashMap<String, Macro>()
    private val slots = LinkedHashMap<Slot, String>()
    private var page = Page(0, 0)

    // Confinés au thread principal, sauf [outputs] (écrit par les threads de lecture des scripts).
    private val running = HashMap<String, ScriptRunner>()
    private val outputs = HashMap<String, ArrayDeque<String>>()

    private val scriptsDir: Path get() = ctx.data.directory.resolve("scripts").createDirectories()
    private val runnerDir: Path get() = ctx.data.directory.resolve(".runners").createDirectories()

    private fun scriptOf(m: Macro): Path = scriptsDir.resolve("${m.id}.${m.shell.extension}")

    // --- cycle de vie ------------------------------------------------------------

    override fun onLoad() {
        ctx.data.getString(KEY_MACROS)?.let { text ->
            runCatching { MiniJson.parse(text) as List<*> }.getOrNull()?.forEach { item ->
                val json = item.obj()
                val id = json.str("id") ?: return@forEach
                macros[id] = Macro.fromJson(json, id)
            }
        }
        ctx.data.getString(KEY_SLOTS)?.split(';')?.forEach { entry ->
            val (slot, id) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            Slot.decode(slot)?.takeIf { id in macros }?.let { slots[it] = id }
        }
        ctx.data.getString(KEY_PAGE)?.let(Slot::decode)?.let { page = it.page }
        ctx.log.info("${macros.size} macro(s), ${slots.size} placée(s)")
    }

    override fun onUnload() {
        // Pas de processus orphelins quand le plugin est déchargé ou mis à jour.
        running.values.forEach(ScriptRunner::stop)
        running.clear()
    }

    override fun onActivate() = redraw()

    override fun onEvent(event: ApcEvent) {
        when (event) {
            is PadEvent -> if (event.pressed) slots[Slot(page, event.x, event.y)]?.let(::toggle)
            is ButtonEvent -> if (event.pressed) when (event.button.group) {
                Button.Group.SCENE -> showPage(page.copy(row = event.button.index))
                Button.Group.TRACK -> showPage(page.copy(col = event.button.index))
                else -> {}
            }
            else -> {}
        }
    }

    // --- exécution -------------------------------------------------------------------

    private fun toggle(id: String) {
        val runner = running[id]
        if (runner != null) {
            ctx.log.info("« ${macros[id]?.name} » : arrêt forcé")
            runner.stop()
        } else {
            start(id)
        }
    }

    private fun start(id: String) {
        val macro = macros[id] ?: return
        val script = scriptOf(macro)
        if (!script.exists()) return ctx.log.warn("« ${macro.name} » : script introuvable")
        synchronized(outputs) { outputs[id] = ArrayDeque() }
        val runner = ScriptRunner(
            script = script, shell = macro.shell, terminal = macro.terminal, runnerDir = runnerDir,
            onLine = { line -> appendOutput(id, line) },
            onExit = { code -> ctx.scope.launch { finished(id, code) } },
        )
        running[id] = runner
        try {
            runner.start()
            ctx.log.info("« ${macro.name} » lancée")
        } catch (t: Throwable) {
            running.remove(id)
            appendOutput(id, "[impossible de lancer le script : ${t.message}]")
            ctx.log.error("« ${macro.name} » : lancement impossible", t)
        }
        changed(save = false)
    }

    private fun finished(id: String, code: Int) {
        running.remove(id)
        appendOutput(id, "[terminé, code $code]")
        ctx.log.info("« ${macros[id]?.name ?: id} » terminée (code $code)")
        changed(save = false)
    }

    /** Appelé depuis les threads de lecture : synchronisé, et l'émission web est thread-safe. */
    private fun appendOutput(id: String, line: String) {
        synchronized(outputs) {
            val lines = outputs.getOrPut(id) { ArrayDeque() }
            lines.addLast(line)
            while (lines.size > MAX_OUTPUT_LINES) lines.removeFirst()
        }
        ctx.web.emit("output", MiniJson.stringify(mapOf("id" to id, "line" to line)))
    }

    // --- LED ---------------------------------------------------------------------------

    private fun redraw() {
        val leds = ctx.leds
        leds.clear()
        slots.forEach { (slot, id) ->
            if (slot.page != page) return@forEach
            val macro = macros[id] ?: return@forEach
            if (id in running) leds.pad(slot.x, slot.y, PadColor.GREEN, Effect.SOLID)
            else leds.pad(slot.x, slot.y, PadColor(macro.color), macro.padEffect)
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
            "save" -> {
                val id = save(json) // avant state() : la réponse doit contenir la macro enregistrée
                return MiniJson.stringify(state() + ("savedId" to id))
            }
            "delete" -> json.str("id")?.let(::delete)
            "run" -> json.str("id")?.takeIf { it in macros }?.let(::toggle)
            "output" -> {
                val id = json.str("id") ?: return null
                val lines = synchronized(outputs) { outputs[id]?.toList().orEmpty() }
                return MiniJson.stringify(mapOf("id" to id, "lines" to lines))
            }
            "page" -> json.int("row")?.let { r -> json.int("col")?.let { c -> showPage(Page(r.coerceIn(0, 4), c.coerceIn(0, 7))) } }
            "place" -> Slot.fromJson(json)?.let { slot -> json.str("id")?.takeIf { it in macros }?.let { slots[slot] = it; changed() } }
            "clear" -> Slot.fromJson(json)?.let { slots.remove(it); changed() }
            "move" -> {
                val from = Slot.fromJson(json["from"].obj()) ?: return null
                val to = Slot.fromJson(json["to"].obj()) ?: return null
                if (from != to) {
                    slots.remove(from)?.let { moving ->
                        slots.remove(to)?.let { slots[from] = it }
                        slots[to] = moving
                        changed()
                    }
                }
            }
            else -> error("action inconnue : $action")
        }
        return MiniJson.stringify(state())
    }

    /** Crée ou met à jour une macro (métadonnées + fichier de script) ; renvoie son id. */
    private fun save(json: Map<String, Any?>): String {
        val id = json.str("id")?.takeIf { it in macros } ?: newId()
        val previous = macros[id]
        val macro = Macro.fromJson(json, id)
        if (previous != null && previous.shell != macro.shell) scriptOf(previous).deleteIfExists()
        val text = json.str("script").orEmpty().replace("\r\n", "\n").let {
            if (macro.shell == Shell.BASH) it else it.replace("\n", "\r\n")
        }
        if (macro.shell == Shell.POWERSHELL) ScriptRunner.writeWithBom(scriptOf(macro), text)
        else scriptOf(macro).toFile().writeText(text, Charsets.UTF_8)
        macros[id] = macro
        ctx.log.info("« ${macro.name} » ${if (previous == null) "créée" else "enregistrée"}")
        changed()
        return id
    }

    private fun delete(id: String) {
        val macro = macros.remove(id) ?: return
        running.remove(id)?.stop()
        slots.entries.removeIf { it.value == id }
        scriptOf(macro).deleteIfExists()
        runnerDir.resolve("run-${scriptOf(macro).fileName}").deleteIfExists()
        ctx.log.info("« ${macro.name} » supprimée")
        changed()
    }

    /** Identifiant unique : les fichiers de script portent cet id, jamais le nom (deux macros peuvent avoir le même nom). */
    private fun newId(): String = generateSequence {
        "m" + System.currentTimeMillis().toString(36) + (0 until 4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
    }.first { it !in macros && Shell.entries.none { shell -> scriptsDir.resolve("$it.${shell.extension}").exists() } }

    private fun state(): Map<String, Any?> = mapOf(
        "shells" to Shell.AVAILABLE.map { mapOf("id" to it.id, "label" to it.label) },
        "effects" to Macro.EFFECTS,
        "current" to mapOf("row" to page.row, "col" to page.col),
        "macros" to macros.values.map { m ->
            m.toJson() + mapOf(
                "script" to runCatching { scriptOf(m).readText().removePrefix("﻿") }.getOrDefault(""),
                "running" to (m.id in running),
            )
        },
        "slots" to slots.map { (slot, id) -> slot.toJson() + ("id" to id) },
    )

    /** Après chaque changement : sauvegarde éventuelle, LED, et mise à jour des pages web ouvertes. */
    private fun changed(save: Boolean = true) {
        if (save) persist()
        redraw()
        ctx.web.emit("state", MiniJson.stringify(state()))
    }

    private fun persist() {
        ctx.data.putString(KEY_MACROS, MiniJson.stringify(macros.values.map(Macro::toJson)))
        ctx.data.putString(KEY_SLOTS, slots.entries.joinToString(";") { (slot, id) -> "${slot.encode()}=$id" })
    }

    private companion object {
        const val KEY_MACROS = "macros"
        const val KEY_SLOTS = "slots"
        const val KEY_PAGE = "page"
        const val MAX_OUTPUT_LINES = 300
    }
}

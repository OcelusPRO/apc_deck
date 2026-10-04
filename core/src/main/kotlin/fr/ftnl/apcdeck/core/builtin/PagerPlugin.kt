package fr.ftnl.apcdeck.core.builtin

import fr.ftnl.apcdeck.api.API_VERSION
import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.ConfigSpec
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.Grid
import fr.ftnl.apcdeck.api.InputBinding
import fr.ftnl.apcdeck.api.InputKind
import fr.ftnl.apcdeck.api.LedState
import fr.ftnl.apcdeck.api.ManagerPlugin
import fr.ftnl.apcdeck.api.PadColor
import fr.ftnl.apcdeck.api.PadEvent
import fr.ftnl.apcdeck.api.PluginManifest
import fr.ftnl.apcdeck.core.PluginSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

val BUILTIN_PLUGINS: List<Pair<PluginManifest, PluginSource.Builtin>> = listOf(
    PluginManifest(
        id = "pager", name = "Pager", version = "1.1.0", main = PagerPlugin::class.java.name,
        apiVersion = API_VERSION, description = "Menu : lance et gère les autres plugins",
        author = "apcdeck", color = PadColor.WHITE.index,
    ) to PluginSource.Builtin(::PagerPlugin),
)

/** Page du menu : [row] = bouton de la colonne verte (0..4), [col] = bouton de la ligne rouge (0..7). */
data class PageId(val row: Int, val col: Int) {
    val label: String get() = "${row + 1}·${col + 1}"

    companion object {
        const val ROWS = 5
        const val COLS = 8
        val ALL: List<PageId> = (0 until ROWS).flatMap { r -> (0 until COLS).map { c -> PageId(r, c) } }
    }
}

/** Emplacement d'un plugin : un pad d'une page. */
data class Slot(val page: PageId, val x: Int, val y: Int) {
    fun encode(): String = "${page.row}.${page.col}.$x.$y"

    companion object {
        fun decode(text: String): Slot? = text.split('.').mapNotNull(String::toIntOrNull).takeIf { it.size == 4 }
            ?.let { (r, c, x, y) -> Slot(PageId(r, c), x, y) }
            ?.takeIf { it.page.row in 0 until PageId.ROWS && it.page.col in 0 until PageId.COLS && it.x in 0 until Grid.COLS && it.y in 0 until Grid.ROWS }
    }
}

/** État publié pour l'éditeur de pages de l'interface. */
data class PagerLayout(val current: PageId, val slots: Map<Slot, String>)

/**
 * Gestionnaire par défaut. 40 pages (colonne verte × ligne rouge) de 40 pads ; chaque pad peut lancer un plugin.
 * Menu : pad = ouvrir le plugin (pulsant si le plugin a déclaré écouter en arrière-plan).
 * Un plugin jamais vu est placé automatiquement au premier emplacement libre ; ensuite la disposition se gère
 * dans l'onglet « Pages » de l'interface (glisser-déposer).
 * Fonctions de gestion assignées aux touches system (Sustain, Play, Rec) depuis l'interface.
 */
class PagerPlugin : ManagerPlugin() {
    object Settings : ConfigSpec() {
        private val SYSTEM = setOf(InputKind.SYSTEM)

        val home = binding("home", "Menu ⇄ dernier plugin", InputBinding.Btn(Button.SUSTAIN), SYSTEM,
            "Affiche le menu des plugins, ou revient au dernier plugin ouvert.")
        val pause = binding("pause", "Pause / reprise", InputBinding.Btn(Button.PLAY), SYSTEM,
            "Met en pause le plugin au premier plan (il ne reçoit plus rien).")
    }

    override val configSpec: ConfigSpec get() = Settings

    // État confiné au thread principal ; l'interface lit [layout] et agit via les méthodes publiques.
    private val slots = LinkedHashMap<Slot, String>()
    private val seen = LinkedHashSet<String>()
    private var current = PageId(0, 0)

    private val _layout = MutableStateFlow(PagerLayout(current, emptyMap()))
    val layout: StateFlow<PagerLayout> = _layout

    override fun onLoad() {
        ctx.data.getString(KEY_SLOTS)?.split(';')?.forEach { entry ->
            val (slot, id) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            Slot.decode(slot)?.let { slots[it] = id }
        }
        ctx.data.getString(KEY_SEEN)?.split(',')?.filter(String::isNotBlank)?.let(seen::addAll)
        ctx.data.getString(KEY_PAGE)?.let(Slot::decode)?.let { current = it.page }
        publish()
    }

    // --- APC -----------------------------------------------------------------

    override fun onActivate() {
        val infos = ctx.host.plugins().associateBy { it.id }
        val leds = ctx.leds
        leds.clear()
        slots.forEach { (slot, id) ->
            val info = infos[id] ?: return@forEach
            if (slot.page == current) leds.pad(slot.x, slot.y, info.color, if (info.listening) Effect.PULSE else Effect.SOLID)
        }
        repeat(PageId.ROWS) { leds.button(Button.scene(it), if (it == current.row) LedState.ON else LedState.OFF) }
        repeat(PageId.COLS) { leds.button(Button.track(it), if (it == current.col) LedState.ON else LedState.OFF) }
    }

    override fun onEvent(event: ApcEvent) {
        when (event) {
            is PadEvent -> if (event.pressed) {
                val id = slots[Slot(current, event.x, event.y)] ?: return
                if (ctx.host.plugins().none { it.id == id }) return ctx.log.warn("'$id' n'est pas chargé")
                ctx.host.activate(id)
            }
            is ButtonEvent -> if (event.pressed) when (event.button.group) {
                Button.Group.SCENE -> showPage(current.copy(row = event.button.index))
                Button.Group.TRACK -> showPage(current.copy(col = event.button.index))
                else -> {}
            }
            else -> {}
        }
    }

    override fun onSystemKey(button: Button, pressed: Boolean) {
        val event = ButtonEvent(button, pressed)
        when {
            ctx.config[Settings.home].matches(event) -> ctx.host.toggleHome()
            ctx.config[Settings.pause].matches(event) -> ctx.host.togglePause()
        }
    }

    override fun onPluginsChanged() {
        // Nouveaux plugins : premier emplacement libre (une seule fois, pour respecter les retraits manuels).
        val fresh = ctx.host.plugins().map { it.id }.filter { it !in seen }
        for (id in fresh) {
            seen += id
            if (id in slots.values) continue
            val free = PageId.ALL.asSequence()
                .flatMap { page -> (0 until Grid.PADS).asSequence().map { Slot(page, it % Grid.COLS, it / Grid.COLS) } }
                .firstOrNull { it !in slots } ?: continue
            slots[free] = id
            ctx.log.info("$id placé en page ${free.page.label}, pad ${free.x + 1}·${free.y + 1}")
        }
        if (fresh.isNotEmpty()) save()
        publish()
    }

    /** Plugin désinstallé : il quitte les pages ; s'il revient, il sera replacé automatiquement. */
    override fun onPluginRemoved(id: String) {
        val removed = slots.entries.removeIf { it.value == id }
        seen -= id
        save()
        publish()
        if (removed) ctx.log.info("$id retiré des pages")
    }

    // --- édition depuis l'interface (thread-safe : exécuté sur le thread principal) ---

    fun showPage(page: PageId) = onMain {
        current = page
        ctx.data.putString(KEY_PAGE, Slot(page, 0, 0).encode())
        publish()
        if (ctx.isForeground) onActivate()
    }

    /** Place [id] sur [slot] (remplace l'occupant éventuel). */
    fun place(slot: Slot, id: String) = edit { slots[slot] = id }

    /** Déplace le contenu de [from] vers [to] ; échange si [to] est occupé. */
    fun move(from: Slot, to: Slot) = edit {
        if (from == to) return@edit
        val moving = slots.remove(from) ?: return@edit
        slots.remove(to)?.let { slots[from] = it }
        slots[to] = moving
    }

    fun clear(slot: Slot) = edit { slots.remove(slot) }

    private fun edit(change: () -> Unit) = onMain {
        change()
        save()
        publish()
        if (ctx.isForeground) onActivate()
    }

    private fun onMain(block: () -> Unit) {
        ctx.scope.launch { block() }
    }

    private fun publish() {
        _layout.value = PagerLayout(current, slots.toMap())
    }

    private fun save() {
        ctx.data.putString(KEY_SLOTS, slots.entries.joinToString(";") { (slot, id) -> "${slot.encode()}=$id" })
        ctx.data.putString(KEY_SEEN, seen.joinToString(","))
    }

    private companion object {
        const val KEY_SLOTS = "slots"
        const val KEY_SEEN = "seen"
        const val KEY_PAGE = "page"
    }
}

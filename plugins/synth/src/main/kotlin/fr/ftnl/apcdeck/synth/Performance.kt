package fr.ftnl.apcdeck.synth

enum class ArpMode(val label: String) { UP("Montant"), DOWN("Descendant"), UP_DOWN("Aller-retour"), RANDOM("Aléatoire") }

/** Durée d'un pas d'arpège, en temps (noires). */
enum class ArpRate(val label: String, val beats: Double) {
    QUARTER("Noire", 1.0), EIGHTH("Croche", 0.5), SIXTEENTH("Double-croche", 0.25), EIGHTH_TRIPLET("Triolet", 1.0 / 3)
}

enum class ChordType(val label: String, val intervals: List<Int>) {
    OFF("Aucun", listOf(0)),
    MAJOR("Majeur", listOf(0, 4, 7)),
    MINOR("Mineur", listOf(0, 3, 7)),
    SEVENTH("Septième", listOf(0, 4, 7, 10)),
    SUS4("Sus4", listOf(0, 5, 7)),
}

/** Modes de jeu (pas des réglages de son : ils ne sont pas dans les presets). */
data class Performance(
    val arp: Boolean = false,
    val arpMode: ArpMode = ArpMode.UP,
    val arpRate: ArpRate = ArpRate.EIGHTH,
    val latch: Boolean = false,
    val chord: ChordType = ChordType.OFF,
    /** Tempo de l'arpège et de l'écho. */
    val bpm: Int = 120,
)

/** Passe à la valeur suivante d'une énumération (pads qui font défiler les choix). */
inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

/**
 * Notes qui doivent sonner, d'après les touches tenues, le latch et les accords. Logique pure (testée seule).
 *
 * Latch : les notes restent après le relâchement des touches ; un nouvel appui, une fois toutes les touches
 * relâchées, remplace l'accord retenu (comme la pédale « hold » d'un synthé).
 */
class NoteState {
    private val physical = LinkedHashMap<Int, Int>() // touche -> vélocité
    private val latched = LinkedHashMap<Int, Int>()

    var latch: Boolean = false
        set(value) {
            field = value
            if (!value) latched.clear()
        }

    var chord: ChordType = ChordType.OFF

    fun press(key: Int, velocity: Int) {
        if (latch && physical.isEmpty()) latched.clear() // nouveau geste : remplace l'accord retenu
        physical[key] = velocity
        if (latch) latched[key] = velocity
    }

    fun release(key: Int) {
        physical.remove(key)
    }

    fun clear() {
        physical.clear()
        latched.clear()
    }

    /** Notes à faire sonner -> vélocité (accords dépliés, bornées à 0..127), triées. */
    fun sounding(): Map<Int, Int> {
        val roots = if (latch) latched + physical else physical
        val out = sortedMapOf<Int, Int>()
        roots.forEach { (key, velocity) ->
            chord.intervals.map { key + it }.filter { it in 0..127 }.forEach { note -> out.merge(note, velocity, ::maxOf) }
        }
        return out
    }
}

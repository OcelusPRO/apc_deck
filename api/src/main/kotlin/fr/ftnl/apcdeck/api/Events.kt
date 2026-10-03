package fr.ftnl.apcdeck.api

/** Dimensions de la grille de pads. (0, 0) = coin haut gauche. */
object Grid {
    const val COLS: Int = 8
    const val ROWS: Int = 5
    const val PADS: Int = COLS * ROWS
}

/** Boutons physiques hors pads et clavier. */
enum class Button(val group: Group, val index: Int) {
    TRACK_1(Group.TRACK, 0), TRACK_2(Group.TRACK, 1), TRACK_3(Group.TRACK, 2), TRACK_4(Group.TRACK, 3),
    TRACK_5(Group.TRACK, 4), TRACK_6(Group.TRACK, 5), TRACK_7(Group.TRACK, 6), TRACK_8(Group.TRACK, 7),
    SCENE_1(Group.SCENE, 0), SCENE_2(Group.SCENE, 1), SCENE_3(Group.SCENE, 2), SCENE_4(Group.SCENE, 3),
    SCENE_5(Group.SCENE, 4),
    STOP_ALL(Group.OTHER, 0),

    /** Touches system : réservées au plugin gestionnaire, jamais transmises aux autres plugins. */
    SHIFT(Group.SYSTEM, 0), PLAY(Group.SYSTEM, 1), REC(Group.SYSTEM, 2), SUSTAIN(Group.SYSTEM, 3);

    enum class Group {
        /** Ligne rouge du bas (LED rouge). */
        TRACK,
        /** Colonne verte de droite (LED verte). */
        SCENE,
        SYSTEM,
        OTHER,
    }

    /** Seules la ligne rouge et la colonne verte ont une LED pilotable. */
    val hasLed: Boolean get() = group == Group.TRACK || group == Group.SCENE

    companion object {
        fun track(index: Int): Button = entries.first { it.group == Group.TRACK && it.index == index }
        fun scene(index: Int): Button = entries.first { it.group == Group.SCENE && it.index == index }
    }
}

sealed interface ApcEvent

data class PadEvent(val x: Int, val y: Int, val pressed: Boolean, val velocity: Int = 127) : ApcEvent {
    val index: Int get() = y * Grid.COLS + x
}

data class ButtonEvent(val button: Button, val pressed: Boolean) : ApcEvent

/** Touche du clavier. [note] est la note MIDI brute : elle change avec les boutons Octave. */
data class KeyEvent(val note: Int, val pressed: Boolean, val velocity: Int) : ApcEvent

/** Potentiomètre 0..7. [delta] = variation signée, [value] = position cumulée 0..127. */
data class KnobEvent(val index: Int, val delta: Int, val value: Int) : ApcEvent

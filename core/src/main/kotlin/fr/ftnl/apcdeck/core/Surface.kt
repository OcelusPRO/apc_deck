package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.Grid
import fr.ftnl.apcdeck.api.LedState
import fr.ftnl.apcdeck.api.Leds
import fr.ftnl.apcdeck.api.PadColor
import fr.ftnl.apcdeck.core.device.ApcDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** État LED complet, pour le miroir dans l'interface. */
data class LedSnapshot(
    val padColors: List<Int>,
    val padEffects: List<Effect>,
    val buttons: Map<Button, LedState>,
) {
    companion object {
        val EMPTY: LedSnapshot = LedSnapshot(List(Grid.PADS) { 0 }, List(Grid.PADS) { Effect.SOLID }, emptyMap())
    }
}

/**
 * Écran LED : on décrit l'état voulu, [flush] n'envoie que les différences.
 * Utilisé uniquement depuis le thread principal.
 */
class Surface : Leds {
    private val ledButtons = Button.entries.filter { it.hasLed }
    private val color = IntArray(Grid.PADS)
    private val effect = IntArray(Grid.PADS) { Effect.SOLID.channel }
    private val buttons = IntArray(ledButtons.size)
    private val sentColor = IntArray(Grid.PADS) { -1 }
    private val sentEffect = IntArray(Grid.PADS) { -1 }
    private val sentButtons = IntArray(ledButtons.size) { -1 }
    private var dirty = true

    private val _snapshot = MutableStateFlow(LedSnapshot.EMPTY)
    val snapshot: StateFlow<LedSnapshot> = _snapshot

    override fun pad(x: Int, y: Int, color: PadColor, effect: Effect) {
        if (x !in 0 until Grid.COLS || y !in 0 until Grid.ROWS) return
        val i = y * Grid.COLS + x
        if (this.color[i] != color.index || this.effect[i] != effect.channel) {
            this.color[i] = color.index
            this.effect[i] = effect.channel
            dirty = true
        }
    }

    override fun button(button: Button, state: LedState) {
        val i = ledButtons.indexOf(button)
        if (i >= 0 && buttons[i] != state.velocity) {
            buttons[i] = state.velocity
            dirty = true
        }
    }

    override fun clear() {
        color.fill(0)
        effect.fill(Effect.SOLID.channel)
        buttons.fill(0)
        dirty = true
    }

    /** Reprend un état complet (LED reçues du PC piloté à distance). */
    fun load(snapshot: LedSnapshot) {
        for (i in 0 until Grid.PADS) {
            color[i] = snapshot.padColors.getOrElse(i) { 0 }
            effect[i] = snapshot.padEffects.getOrElse(i) { Effect.SOLID }.channel
        }
        ledButtons.forEachIndexed { i, b -> buttons[i] = snapshot.buttons[b]?.velocity ?: 0 }
        dirty = true
    }

    /** Force le renvoi complet au prochain flush (reconnexion, changement de mode). */
    fun invalidate() {
        sentColor.fill(-1)
        sentEffect.fill(-1)
        sentButtons.fill(-1)
    }

    /** Envoie les différences à l'appareil (s'il est connecté) ; une exception signale un appareil perdu. */
    fun flush(device: ApcDevice?) {
        if (dirty) {
            dirty = false
            _snapshot.value = LedSnapshot(
                color.toList(),
                effect.map(Effect::ofChannel),
                ledButtons.withIndex().associate { (i, b) -> b to LedState.entries.first { it.velocity == buttons[i] } },
            )
        }
        if (device == null || !device.isOpen) return
        for (i in 0 until Grid.PADS) {
            if (sentColor[i] != color[i] || sentEffect[i] != effect[i]) {
                device.setPad(i % Grid.COLS, i / Grid.COLS, color[i], effect[i])
                sentColor[i] = color[i]
                sentEffect[i] = effect[i]
            }
        }
        for (i in ledButtons.indices) {
            if (sentButtons[i] != buttons[i]) {
                device.setButton(ledButtons[i], buttons[i])
                sentButtons[i] = buttons[i]
            }
        }
    }
}

/** Écran factice donné aux plugins qui n'ont pas la main. */
object NullLeds : Leds {
    override fun pad(x: Int, y: Int, color: PadColor, effect: Effect) {}
    override fun button(button: Button, state: LedState) {}
    override fun clear() {}
}

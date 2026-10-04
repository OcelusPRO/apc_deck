package fr.ftnl.apcdeck.android

import android.app.Application
import fr.ftnl.apcdeck.core.AppBridge
import fr.ftnl.apcdeck.core.Engine
import fr.ftnl.apcdeck.core.PluginSource
import fr.ftnl.apcdeck.core.builtin.BUILTIN_PLUGINS
import fr.ftnl.apcdeck.core.parseManifest
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.PluginManifest
import fr.ftnl.apcdeck.soundboard.SoundboardPlugin
import fr.ftnl.apcdeck.synth.SynthPlugin
import java.io.FileNotFoundException

/** Le moteur vit aussi longtemps que le processus (le service le garde en vie hors de l'écran). */
class ApcApp : Application() {
    val engine: Engine by lazy {
        val home = filesDir.toPath().resolve("apcdeck")
        Engine(home, AndroidPlatform(this), BUILTIN_PLUGINS + bundled("synth", ::SynthPlugin) + bundled("soundboard", ::SoundboardPlugin)).also {
            it.start()
            AppBridge(it).start()
        }
    }

    /**
     * Plugin officiel compilé dans l'app : son plugin.json et sa page web sont dans les assets (plugins/<id>/),
     * copiés au build depuis le module du plugin.
     */
    private fun bundled(id: String, factory: () -> ApcPlugin): Pair<PluginManifest, PluginSource.Builtin> {
        val manifest = parseManifest(assets.open("plugins/$id/plugin.json").use { it.readBytes().decodeToString() })
        return manifest to PluginSource.Builtin(factory) { path ->
            try {
                assets.open("plugins/$id/$path").use { it.readBytes() }
            } catch (_: FileNotFoundException) {
                null
            }
        }
    }
}

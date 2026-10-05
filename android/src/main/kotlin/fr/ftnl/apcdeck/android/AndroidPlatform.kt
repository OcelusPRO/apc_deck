package fr.ftnl.apcdeck.android

import android.content.Context
import android.os.Build
import android.provider.Settings
import fr.ftnl.apcdeck.api.Audio
import fr.ftnl.apcdeck.core.Platform
import fr.ftnl.apcdeck.core.PluginLoader
import fr.ftnl.apcdeck.core.device.MidiBackend
import java.nio.file.Path

class AndroidPlatform(private val context: Context) : Platform {
    override val id: String = "android"
    override val midi: MidiBackend = AndroidMidi(context)
    override val audio: Audio = AndroidAudio
    override val pluginLoader: PluginLoader = DexPluginLoader

    override fun openFolder(dir: Path) = throw UnsupportedOperationException("pas de gestionnaire de fichiers sur Android")

    /** Nom donné à l'appareil dans les réglages du téléphone, sinon son modèle. */
    override val deviceName: String
        get() = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"
}

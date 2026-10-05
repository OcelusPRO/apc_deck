package fr.ftnl.apcdeck.android

import dalvik.system.DexClassLoader
import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.PluginManifest
import fr.ftnl.apcdeck.core.LoadedPlugin
import fr.ftnl.apcdeck.core.PluginLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Plugins tiers sur Android : Android n'exécute pas les classes JVM d'un jar ; le jar doit contenir aussi un
 * `classes.dex` (produit par l'outil d8 du SDK Android : `d8 --release --min-api 26 --output plugin-dex.zip plugin.jar`
 * puis y ajouter plugin.json et web/). Un classloader par plugin, qui ne voit que l'API, Kotlin et les coroutines.
 */
object DexPluginLoader : PluginLoader {
    override fun load(jar: Path, manifest: PluginManifest, cacheDir: Path): LoadedPlugin {
        ZipFile(jar.toFile()).use { zip ->
            require(zip.getEntry("classes.dex") != null) {
                "${manifest.name} n'est pas compilé pour Android (pas de classes.dex dans le jar : voir l'outil d8 du SDK Android)"
            }
        }
        val copy = cacheDir.resolve("${manifest.id}-${System.nanoTime()}.jar")
        Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING)
        // Android 14 refuse de charger du code depuis un fichier modifiable.
        copy.toFile().setReadOnly()
        val loader = DexClassLoader(copy.toString(), null, null, ApiOnlyClassLoader(DexPluginLoader::class.java.classLoader!!))
        try {
            val cls = loader.loadClass(manifest.main)
            require(ApcPlugin::class.java.isAssignableFrom(cls)) { "${manifest.main} n'hérite pas de ApcPlugin" }
            return Loaded(cls.getDeclaredConstructor().newInstance() as ApcPlugin, copy.toFile())
        } catch (t: Throwable) {
            copy.toFile().delete()
            throw t
        }
    }

    private class Loaded(override val instance: ApcPlugin, private val file: File) : LoadedPlugin {
        override fun resource(path: String): ByteArray? = ZipFile(file).use { zip ->
            zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
        }

        override fun close() {
            file.setWritable(true)
            file.delete()
        }
    }

    /** Parent des classloaders de plugins : le système, plus l'API, Kotlin et les coroutines de l'application. */
    private class ApiOnlyClassLoader(private val app: ClassLoader) : ClassLoader(String::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            if (SHARED.any(name::startsWith)) app.loadClass(name) else super.loadClass(name, resolve)

        companion object {
            val SHARED = listOf("fr.ftnl.apcdeck.api.", "kotlin.", "kotlinx.coroutines.")
        }
    }
}

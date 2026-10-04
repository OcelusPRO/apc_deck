package fr.ftnl.apcdeck.desktop

import fr.ftnl.apcdeck.api.ApcPlugin
import fr.ftnl.apcdeck.api.PluginManifest
import fr.ftnl.apcdeck.core.LoadedPlugin
import fr.ftnl.apcdeck.core.PluginLoader
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists

/** Plugins en classes JVM : un classloader par jar, qui ne voit que l'API (pas le cœur ni les autres plugins). */
object JarPluginLoader : PluginLoader {
    override fun load(jar: Path, manifest: PluginManifest, cacheDir: Path): LoadedPlugin {
        // Copie : sous Windows un jar ouvert est verrouillé, on veut pouvoir le remplacer à chaud.
        val copy = cacheDir.resolve("${manifest.id}-${System.nanoTime()}.jar")
        Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING)
        val loader = URLClassLoader("plugin-${manifest.id}", arrayOf(copy.toUri().toURL()),
            ApiOnlyClassLoader(JarPluginLoader::class.java.classLoader))
        try {
            val cls = loader.loadClass(manifest.main)
            require(ApcPlugin::class.java.isAssignableFrom(cls)) { "${manifest.main} n'hérite pas de ApcPlugin" }
            val instance = cls.getDeclaredConstructor().newInstance() as ApcPlugin
            return Loaded(instance, loader, copy)
        } catch (t: Throwable) {
            runCatching { loader.close() }
            runCatching { copy.deleteIfExists() }
            throw t
        }
    }

    private class Loaded(override val instance: ApcPlugin, private val loader: URLClassLoader, private val copy: Path) : LoadedPlugin {
        // Sans cache : sinon Java garde le jar ouvert (et verrouillé sous Windows) après le déchargement du plugin.
        override fun resource(path: String): ByteArray? =
            loader.findResource(path)?.openConnection()?.apply { useCaches = false }?.getInputStream()?.use { it.readBytes() }

        override fun close() {
            runCatching { loader.close() }
            runCatching { copy.deleteIfExists() }
        }
    }
}

/**
 * Parent des classloaders de plugins : n'expose que l'API, la stdlib Kotlin et les coroutines (plus le JDK). Les plugins ne voient pas le cœur ni l'interface, et peuvent embarquer leurs propres libs.
 */
private class ApiOnlyClassLoader(private val app: ClassLoader) : ClassLoader("apcdeck-api", getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (SHARED.any(name::startsWith)) app.loadClass(name) else super.loadClass(name, resolve)

    override fun getResource(name: String): URL? =
        if (SHARED.any { name.startsWith(it.replace('.', '/')) }) app.getResource(name) else super.getResource(name)

    companion object {
        val SHARED = listOf("fr.ftnl.apcdeck.api.", "kotlin.", "kotlinx.coroutines.")
    }
}

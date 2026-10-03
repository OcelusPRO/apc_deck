package fr.ftnl.apcdeck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Dernière release d'un dépôt GitHub. [version] : le tag sans le « v » initial. */
data class Release(val version: String, val pageUrl: String?, val notes: String?, val assets: List<Asset>) {
    data class Asset(val name: String, val url: String)

    fun asset(predicate: (String) -> Boolean): Asset? = assets.firstOrNull { predicate(it.name.lowercase()) }
}

/** Accès aux releases GitHub (API publique, sans compte : 60 requêtes par heure, largement assez). */
class GitHub(private val userAgent: String) {

    /** Dernière release publiée de [repo] (`owner/repo`) ; null si le dépôt n'en a aucune. */
    fun latestRelease(repo: String): Release? {
        val connection = open("https://api.github.com/repos/$repo/releases/latest") ?: return null
        val json = Json.parseToJsonElement(connection.inputStream.use { it.readBytes().decodeToString() }).jsonObject
        val tag = json.string("tag_name") ?: error("release sans tag sur $repo")
        val assets = json["assets"]?.jsonArray.orEmpty().mapNotNull { a ->
            val o = a.jsonObject
            val name = o.string("name") ?: return@mapNotNull null
            val url = o.string("browser_download_url") ?: return@mapNotNull null
            Release.Asset(name, url)
        }
        return Release(tag.removePrefix("v"), json.string("html_url"), json.string("body"), assets)
    }

    /** Télécharge [url] dans [target] (via un fichier .part) ; [onProgress] reçoit 0..1 au fil de l'eau. */
    fun download(url: String, target: Path, onProgress: (Double) -> Unit = {}): Path {
        val connection = open(url) ?: error("fichier introuvable : $url")
        val total = connection.contentLengthLong
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.part")
        connection.inputStream.use { input ->
            Files.newOutputStream(partial).use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0 && done - lastReport > total / 100) {
                        lastReport = done
                        onProgress(done.toDouble() / total)
                    }
                }
            }
        }
        return Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Connexion ouverte et réussie ; null pour une réponse 404. */
    private fun open(url: String): HttpURLConnection? {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true // les fichiers des releases sont servis via une redirection
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val code = c.responseCode
        if (code in 200..299) return c
        c.disconnect()
        if (code == 404) return null
        error("GitHub a répondu $code")
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        private val URL = Regex("""^(?:https?://|git@)?(?:www\.)?github\.com[/:]([\w.-]+)/([\w.-]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE)
        private val SHORT = Regex("""^([\w.-]+)/([\w.-]+)$""")

        /**
         * `owner/repo` d'une adresse de dépôt : `https://github.com/owner/repo(.git)`, `git@github.com:owner/repo.git`
         * ou directement `owner/repo`. null pour une adresse non reconnue (seul GitHub est pris en charge).
         */
        fun repoOf(address: String): String? {
            val a = address.trim()
            val m = URL.find(a) ?: SHORT.find(a) ?: return null
            return "${m.groupValues[1]}/${m.groupValues[2]}"
        }
    }
}

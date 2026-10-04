package fr.ftnl.apcdeck.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Dernière release d'un dépôt. [version] : le tag sans le « v » initial. */
data class Release(val version: String, val pageUrl: String?, val notes: String?, val assets: List<Asset>) {
    data class Asset(val name: String, val url: String)

    /** Premier fichier dont le nom (en minuscules) vérifie [predicate]. */
    fun asset(predicate: (String) -> Boolean): Asset? = assets.firstOrNull { predicate(it.name.lowercase()) }

    /** Jar du plugin [id] : fichier nommé `<id>.jar` (nom affiché ou fin de l'URL : sur GitLab le nom d'un lien est libre). */
    fun pluginJar(id: String): Asset? = "$id.jar".let { file ->
        assets.firstOrNull { it.name.equals(file, ignoreCase = true) || it.url.substringAfterLast('/').equals(file, ignoreCase = true) }
    }
}

/** Forges dont on sait lire les releases. */
enum class Forge { GITHUB, GITLAB, GITEA }

/**
 * Dépôt git désigné par son adresse : [host] + [path] (`owner/repo`, ou `groupe/sous-groupe/projet` sur GitLab).
 * Formes acceptées : `https://hôte/chemin(.git)`, `git@hôte:chemin.git`, `hôte/chemin`, et `owner/repo` (GitHub).
 */
data class RepoRef(val host: String, val path: String, val scheme: String = "https") {
    val base: String get() = "$scheme://$host"

    /** Clé unique (un même dépôt partagé par plusieurs plugins n'est interrogé qu'une fois). */
    val key: String get() = "$host/$path".lowercase()

    companion object {
        private val SHORT = Regex("""^([\w-]+)/([\w.-]+)$""") // owner/repo : pas de point dans un nom de compte GitHub
        private val HTTP = Regex("""^(https?)://([^/\s]+)/(\S+)$""", RegexOption.IGNORE_CASE)
        private val SSH = Regex("""^(?:ssh://)?git@([\w.-]+)(?::\d+)?[:/](\S+)$""")
        private val BARE = Regex("""^([\w-]+(?:\.[\w-]+)+)/(\S+)$""")

        /** Hôtes dont les adresses ne comportent que owner/repo (le reste de l'URL est une page du dépôt). */
        private val TWO_SEGMENTS = setOf("github.com", "codeberg.org", "gitea.com", "bitbucket.org")

        fun parse(address: String): RepoRef? {
            val a = address.trim()
            SHORT.find(a)?.let { return RepoRef("github.com", "${it.groupValues[1]}/${it.groupValues[2]}") }
            val (scheme, rawHost, rawPath) = HTTP.find(a)?.let { Triple(it.groupValues[1].lowercase(), it.groupValues[2], it.groupValues[3]) }
                ?: SSH.find(a)?.let { Triple("https", it.groupValues[1], it.groupValues[2]) }
                ?: BARE.find(a)?.let { Triple("https", it.groupValues[1], it.groupValues[2]) }
                ?: return null
            val host = rawHost.lowercase().removePrefix("www.")
            var segments = rawPath.substringBefore('?').substringBefore('#')
                .substringBefore("/-/") // GitLab : https://gitlab.com/groupe/projet/-/releases
                .removeSuffix("/").removeSuffix(".git").split('/').filter { it.isNotEmpty() }
            if (host in TWO_SEGMENTS) segments = segments.take(2)
            if (segments.size < 2) return null
            return RepoRef(host, segments.joinToString("/"), scheme)
        }
    }
}

/**
 * Lecture des releases sur GitHub, GitLab (gitlab.com ou auto-hébergé), Gitea/Forgejo (Codeberg, auto-hébergé) et
 * GitHub Enterprise, sans compte (dépôts publics). La forge d'un hôte inconnu est détectée à la première requête.
 * Aussi : téléchargement de fichiers, conditionnel (ETag / Last-Modified) pour suivre une URL directe.
 */
class ReleaseClient(private val userAgent: String) {
    private val detected = ConcurrentHashMap<String, Forge>()

    /** Dernière release du dépôt [repository] ; null s'il n'en a publié aucune. */
    fun latestRelease(repository: String): Release? = latestRelease(
        RepoRef.parse(repository) ?: error("adresse de dépôt non reconnue : « $repository »"),
    )

    fun latestRelease(ref: RepoRef): Release? = when (forgeOf(ref)) {
        Forge.GITHUB -> {
            val api = if (ref.host == "github.com") "https://api.github.com" else "${ref.base}/api/v3"
            getJson("$api/repos/${ref.path}/releases/latest")?.let(::parseGitHub)
        }
        Forge.GITEA -> getJson("${ref.base}/api/v1/repos/${ref.path}/releases/latest")?.let(::parseGitHub)
        Forge.GITLAB -> parseGitLab(
            getJson("${ref.base}/api/v4/projects/${encode(ref.path)}/releases?per_page=1")
                ?: error("projet GitLab introuvable : ${ref.host}/${ref.path}"),
            ref,
        )
    }

    private fun forgeOf(ref: RepoRef): Forge = KNOWN[ref.host] ?: detected.getOrPut(ref.host) {
        if (ref.host == "bitbucket.org") error("Bitbucket ne publie pas de releases : utiliser une URL directe (updateUrl)")
        // Auto-hébergé : on essaie les API des forges connues sur ce dépôt.
        when {
            status("${ref.base}/api/v4/projects/${encode(ref.path)}") == 200 -> Forge.GITLAB
            status("${ref.base}/api/v1/repos/${ref.path}") == 200 -> Forge.GITEA
            status("${ref.base}/api/v3/repos/${ref.path}") == 200 -> Forge.GITHUB
            else -> error("${ref.host} : dépôt introuvable ou forge non prise en charge (GitHub, GitLab, Gitea/Forgejo)")
        }
    }

    /** Télécharge [url] dans [target] (via un fichier .part) ; [onProgress] reçoit 0..1 au fil de l'eau. */
    fun download(url: String, target: Path, onProgress: (Double) -> Unit = {}): Path {
        val connection = open(url, accept = "*/*") ?: error("fichier introuvable : $url")
        return save(connection, target, onProgress)
    }

    /** Validateurs HTTP d'un fichier déjà téléchargé. */
    data class Validators(val etag: String?, val lastModified: String?)

    /**
     * Télécharge [url] dans [target] seulement s'il a changé depuis [known] (requête conditionnelle) : renvoie
     * les nouveaux validateurs, ou null si le serveur répond « non modifié » ([target] est alors inchangé).
     */
    fun downloadIfChanged(url: String, target: Path, known: Validators?): Validators? {
        val headers = buildMap {
            known?.etag?.let { put("If-None-Match", it) }
            known?.lastModified?.let { put("If-Modified-Since", it) }
        }
        val connection = open(url, accept = "*/*", headers = headers, notModifiedIsNull = true) ?: return null
        val validators = Validators(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
        save(connection, target) {}
        return validators
    }

    private fun save(connection: HttpURLConnection, target: Path, onProgress: (Double) -> Unit): Path {
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

    private fun getJson(url: String): JsonElement? =
        open(url, accept = "application/json")?.let { c -> Json.parseToJsonElement(c.inputStream.use { it.readBytes().decodeToString() }) }

    private fun status(url: String): Int = runCatching {
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            responseCode.also { disconnect() }
        }
    }.getOrDefault(-1)

    /** Connexion réussie ; null pour 404 (et pour 304 si [notModifiedIsNull]). */
    private fun open(
        url: String, accept: String, headers: Map<String, String> = emptyMap(), notModifiedIsNull: Boolean = false,
    ): HttpURLConnection? {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true // les fichiers des releases sont souvent servis via une redirection
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", accept)
        headers.forEach(c::setRequestProperty)
        val code = c.responseCode
        if (code in 200..299) return c
        c.disconnect()
        if (code == 404 || (notModifiedIsNull && code == 304)) return null
        if (code == 401 || code == 403) error("${URI(url).host} refuse l'accès ($code) : dépôt privé ou releases réservées aux membres")
        error("${URI(url).host} a répondu $code")
    }

    companion object {
        private val KNOWN = mapOf(
            "github.com" to Forge.GITHUB,
            "gitlab.com" to Forge.GITLAB,
            "codeberg.org" to Forge.GITEA,
            "gitea.com" to Forge.GITEA,
        )

        private fun encode(path: String) = URLEncoder.encode(path, Charsets.UTF_8)

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        /** Release GitHub (même format sur Gitea/Forgejo) : tag_name, html_url, body, assets[].browser_download_url. */
        fun parseGitHub(json: JsonElement): Release {
            val o = json.jsonObject
            val tag = o.string("tag_name") ?: error("release sans tag")
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                val asset = a as? JsonObject ?: return@mapNotNull null
                Release.Asset(asset.string("name") ?: return@mapNotNull null, asset.string("browser_download_url") ?: return@mapNotNull null)
            }
            return Release(tag.removePrefix("v"), o.string("html_url"), o.string("body"), assets)
        }

        /** Liste de releases GitLab (la plus récente en premier) : tag_name, description, assets.links[]. */
        fun parseGitLab(json: JsonElement, ref: RepoRef): Release? {
            val o = json.jsonArray.firstOrNull()?.jsonObject ?: return null
            val tag = o.string("tag_name") ?: error("release sans tag")
            val links = (o["assets"] as? JsonObject)?.get("links") as? JsonArray
            val assets = links.orEmpty().mapNotNull { l ->
                val link = l as? JsonObject ?: return@mapNotNull null
                val url = link.string("direct_asset_url") ?: link.string("url") ?: return@mapNotNull null
                Release.Asset(link.string("name") ?: url.substringAfterLast('/'), url)
            }
            val page = ((o["_links"] as? JsonObject)?.get("self") as? JsonPrimitive)?.contentOrNull
                ?: "${ref.base}/${ref.path}/-/releases/$tag"
            return Release(tag.removePrefix("v"), page, o.string("description"), assets)
        }

        fun sha256(file: Path): String =
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
    }
}

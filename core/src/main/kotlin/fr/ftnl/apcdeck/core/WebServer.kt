package fr.ftnl.apcdeck.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fr.ftnl.apcdeck.api.jsonQuote
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Ce que l'interface de l'application demande au serveur. */
interface AppRoutes {
    /** Événements envoyés à une interface qui vient de se connecter (état complet). */
    fun snapshot(): List<Pair<String, String>>

    /** Commande de l'interface ; réponse JSON (ou null). */
    fun command(name: String, body: ByteArray, query: Map<String, String>): Result<String?>
}

/**
 * Serveur HTTP local : interface de l'application et interfaces web des plugins.
 *
 *   GET  /<jeton>/                           interface de l'application (ressources ui/ du cœur, compilées par Vite)
 *   GET  /<jeton>/assets/<fichier>           ses fichiers JS/CSS
 *   GET  /<jeton>/app/events                 flux SSE de l'état de l'application
 *   POST /<jeton>/app/cmd/<nom>              commandes de l'interface
 *   GET  /apcdeck.js                         client JS des plugins (apcdeck.call / apcdeck.on)
 *   GET  /<jeton>/p/<id>/<chemin>            fichier web/<chemin> du jar du plugin
 *   POST /<jeton>/api/<id>/call/<action>     -> ApcPlugin.onWebCall (corps et réponse en JSON)
 *   GET  /<jeton>/api/<id>/events            flux SSE des ctx.web.emit(...)
 *
 * N'écoute que sur 127.0.0.1 ; le jeton empêche une page tierce ouverte dans un navigateur de piloter l'application.
 */
class WebServer(
    private val token: String,
    preferredPort: Int,
    private val resource: (pluginId: String, path: String) -> URL?,
    private val call: (pluginId: String, action: String, body: String) -> Result<String?>,
    private val log: (String, Throwable?) -> Unit,
) {
    private val server: HttpServer = try {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), preferredPort), 0)
    } catch (_: BindException) {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0) // port occupé : port libre
    }
    private val streams = ConcurrentHashMap<String, CopyOnWriteArrayList<OutputStream>>()

    /** Branché par l'application (interface principale). */
    var app: AppRoutes? = null

    val port: Int get() = server.address.port
    val uiUrl: String get() = "http://127.0.0.1:$port/$token/"

    fun urlFor(pluginId: String, version: String): String = "/$token/p/$pluginId/index.html?v=$version"

    fun start() {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "apc-web").apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (_: IOException) {
                // client parti
            } catch (t: Throwable) {
                log("erreur du serveur web", t)
                runCatching { send(exchange, 500, "text/plain", "erreur interne".toByteArray()) }
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    /** Envoie un événement aux pages ouvertes du plugin. */
    fun emit(pluginId: String, event: String, json: String) = broadcast(pluginId, event, json)

    /** Envoie un événement aux interfaces de l'application ouvertes. */
    fun emitApp(event: String, json: String) = broadcast(APP_STREAM, event, json)

    private fun broadcast(stream: String, event: String, json: String) {
        val payload = sse(event, json)
        streams[stream]?.forEach { out ->
            try {
                synchronized(out) {
                    out.write(payload)
                    out.flush()
                }
            } catch (_: IOException) {
                streams[stream]?.remove(out)
            }
        }
    }

    private fun sse(event: String, json: String) = "data: {\"event\":${jsonQuote(event)},\"data\":$json}\n\n".toByteArray()

    private fun route(exchange: HttpExchange) {
        val parts = exchange.requestURI.path.split('/').filter(String::isNotEmpty)
        val method = exchange.requestMethod
        when {
            parts == listOf("apcdeck.js") -> send(exchange, 200, "text/javascript", CLIENT_JS.toByteArray())
            parts.isEmpty() || parts[0] != token -> send(exchange, 404, "text/plain", "introuvable".toByteArray())
            parts.size == 1 && !exchange.requestURI.path.endsWith("/") -> {
                // Les chemins relatifs de la page supposent un "/" final.
                exchange.responseHeaders.add("Location", "/$token/")
                send(exchange, 302, "text/plain", ByteArray(0))
            }
            parts.size == 1 -> serveUi(exchange, "index.html")
            parts[1] == "ui" -> serveUi(exchange, parts.drop(2).joinToString("/"))
            parts[1] == "assets" -> serveUi(exchange, parts.drop(1).joinToString("/")) // fichiers compilés par Vite
            parts[1] == "app" && parts.getOrNull(2) == "events" -> stream(exchange, APP_STREAM, app?.snapshot().orEmpty())
            parts[1] == "app" && parts.getOrNull(2) == "cmd" && parts.size == 4 && method == "POST" -> appCommand(exchange, parts[3])
            parts.size < 3 -> send(exchange, 404, "text/plain", "introuvable".toByteArray())
            parts[1] == "p" -> servePluginFile(exchange, parts[2], parts.drop(3).joinToString("/").ifEmpty { "index.html" })
            parts[1] == "api" && parts.getOrNull(3) == "call" && parts.size == 5 && method == "POST" ->
                pluginCall(exchange, parts[2], parts[4])
            parts[1] == "api" && parts.getOrNull(3) == "events" -> stream(exchange, parts[2], emptyList())
            else -> send(exchange, 404, "text/plain", "introuvable".toByteArray())
        }
    }

    private fun validPath(path: String) = path.isNotEmpty() && path.split('/').none { it == ".." || it.isEmpty() }

    private fun serveUi(exchange: HttpExchange, path: String) {
        if (!validPath(path)) return send(exchange, 400, "text/plain", "chemin invalide".toByteArray())
        val url = WebServer::class.java.getResource("/ui/$path") ?: return send(exchange, 404, "text/plain", "introuvable : $path".toByteArray())
        send(exchange, 200, contentType(path), url.openStream().use { it.readBytes() })
    }

    private fun servePluginFile(exchange: HttpExchange, pluginId: String, path: String) {
        if (!validPath(path)) return send(exchange, 400, "text/plain", "chemin invalide".toByteArray())
        val url = resource(pluginId, path) ?: return send(exchange, 404, "text/plain", "introuvable : $path".toByteArray())
        // Sans cache : sinon Java garde le jar ouvert (et verrouillé sous Windows) après le déchargement du plugin.
        val bytes = url.openConnection().apply { useCaches = false }.getInputStream().use { it.readBytes() }
        send(exchange, 200, contentType(path), bytes)
    }

    private fun appCommand(exchange: HttpExchange, name: String) {
        val routes = app ?: return send(exchange, 503, "text/plain", "interface indisponible".toByteArray())
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
        respond(exchange, routes.command(name, exchange.requestBody.readBytes(), query))
    }

    private fun pluginCall(exchange: HttpExchange, pluginId: String, action: String) {
        val body = exchange.requestBody.readBytes().decodeToString().ifBlank { "null" }
        respond(exchange, call(pluginId, URLDecoder.decode(action, Charsets.UTF_8), body))
    }

    private fun respond(exchange: HttpExchange, result: Result<String?>) = result.fold(
        onSuccess = { json ->
            if (json == null) send(exchange, 204, "application/json", ByteArray(0))
            else send(exchange, 200, "application/json", json.toByteArray())
        },
        onFailure = { send(exchange, 400, "application/json", "{\"error\":${jsonQuote(it.message ?: "erreur")}}".toByteArray()) },
    )

    /** Flux SSE : la connexion reste ouverte (un thread du pool par page ouverte). */
    private fun stream(exchange: HttpExchange, key: String, initial: List<Pair<String, String>>) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        val list = streams.computeIfAbsent(key) { CopyOnWriteArrayList() }
        try {
            synchronized(out) {
                initial.forEach { (event, json) -> out.write(sse(event, json)) }
                out.flush()
            }
            list += out
            while (true) {
                Thread.sleep(15_000)
                synchronized(out) {
                    out.write(": ping\n\n".toByteArray())
                    out.flush()
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            list.remove(out)
        }
    }

    private fun send(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", if (type.startsWith("text/") || type.endsWith("json")) "$type; charset=utf-8" else type)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    private companion object {
        const val APP_STREAM = "#app"

        /** Client injecté dans les pages des plugins : déduit le jeton et l'id du plugin de l'URL de la page. */
        val CLIENT_JS = """
            (() => {
              const match = location.pathname.match(/^\/([0-9a-f]+)\/p\/([^/]+)\//);
              if (!match) { console.error("apcdeck.js : page hors de l'application"); return; }
              const [, token, id] = match;
              const api = "/" + token + "/api/" + id;
              const listeners = {};
              const source = new EventSource(api + "/events");
              source.onmessage = (message) => {
                const { event, data } = JSON.parse(message.data);
                (listeners[event] || []).forEach((cb) => cb(data));
              };
              window.apcdeck = {
                id,
                /** Appelle ApcPlugin.onWebCall(action, JSON(data)) ; renvoie la réponse décodée (ou null). */
                async call(action, data) {
                  const response = await fetch(api + "/call/" + encodeURIComponent(action), {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(data === undefined ? null : data),
                  });
                  const text = await response.text();
                  if (!response.ok) throw new Error(text);
                  return text ? JSON.parse(text) : null;
                },
                /** Écoute les événements envoyés par ctx.web.emit(event, json). */
                on(event, callback) {
                  (listeners[event] = listeners[event] || []).push(callback);
                },
              };
            })();
        """.trimIndent()
    }
}

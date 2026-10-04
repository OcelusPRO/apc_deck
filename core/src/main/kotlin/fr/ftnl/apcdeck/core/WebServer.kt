package fr.ftnl.apcdeck.core

import fr.ftnl.apcdeck.api.jsonQuote
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
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
 * Serveur HTTP/1.1 minimal sur des sockets (com.sun.net.httpserver n'existe pas sur Android) : une requête par
 * connexion, un thread par connexion (les flux SSE gardent la leur).
 */
class WebServer(
    private val token: String,
    preferredPort: Int,
    private val resource: (pluginId: String, path: String) -> ByteArray?,
    private val call: (pluginId: String, action: String, body: String) -> Result<String?>,
    private val log: (String, Throwable?) -> Unit,
) {
    private val server: ServerSocket = ServerSocket().apply {
        reuseAddress = true
        try {
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), preferredPort), 50)
        } catch (_: BindException) {
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50) // port occupé : port libre
        }
    }
    private val streams = ConcurrentHashMap<String, CopyOnWriteArrayList<OutputStream>>()
    private var pool: ExecutorService? = null

    /** Branché par l'application (interface principale). */
    var app: AppRoutes? = null

    val port: Int get() = server.localPort
    val uiUrl: String get() = "http://127.0.0.1:$port/$token/"

    fun urlFor(pluginId: String, version: String): String = "/$token/p/$pluginId/index.html?v=$version"

    fun start() {
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "apc-web").apply { isDaemon = true } }
        pool = executor
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break // serveur fermé
                }
                executor.execute { handle(socket) }
            }
        }, "apc-web-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server.close() }
        pool?.shutdownNow()
    }

    /** Envoie un événement aux pages ouvertes du plugin. */
    fun emit(pluginId: String, event: String, json: String) = broadcast(pluginId, event, json)

    /** Vrai si au moins une interface de l'application est ouverte (connectée au flux d'état). */
    val hasAppClients: Boolean get() = !streams[APP_STREAM].isNullOrEmpty()

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

    // --- HTTP ----------------------------------------------------------------------

    private class Request(val method: String, val path: String, val rawQuery: String, val body: ByteArray)

    /** Réponse en cours : en-têtes ajoutés avant [send]. */
    private class Exchange(val request: Request, val out: OutputStream) {
        val headers = mutableListOf<Pair<String, String>>()
        var sent = false
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val out = socket.getOutputStream().buffered()
                val request = readRequest(input) ?: return
                socket.soTimeout = 0 // flux SSE : pas de limite une fois la requête lue
                val exchange = Exchange(request, out)
                try {
                    route(exchange)
                } catch (e: IOException) {
                    throw e
                } catch (t: Throwable) {
                    log("erreur du serveur web", t)
                    if (!exchange.sent) send(exchange, 500, "text/plain", "erreur interne".toByteArray())
                }
                out.flush()
            } catch (_: IOException) {
                // client parti
            } catch (_: SocketException) {
            }
        }
    }

    /** Lit la ligne de requête, les en-têtes et le corps (Content-Length) ; null si la requête est invalide. */
    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) return null
        var length = 0L
        while (true) {
            val header = readLine(input) ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0 && header.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = header.substring(colon + 1).trim().toLongOrNull() ?: return null
            }
        }
        require(length in 0..MAX_BODY) { "corps de requête trop gros" }
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) return null
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        return Request(parts[0], URLDecoder.decode(path.replace("+", "%2B"), "UTF-8"), target.substringAfter('?', ""), body)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > 16_384) throw IOException("ligne trop longue")
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun route(exchange: Exchange) {
        val requestPath = exchange.request.path
        val parts = requestPath.split('/').filter(String::isNotEmpty)
        val method = exchange.request.method
        when {
            parts == listOf("apcdeck.js") -> send(exchange, 200, "text/javascript", CLIENT_JS.toByteArray())
            parts.isEmpty() || parts[0] != token -> send(exchange, 404, "text/plain", "introuvable".toByteArray())
            parts.size == 1 && !requestPath.endsWith("/") -> {
                // Les chemins relatifs de la page supposent un "/" final.
                exchange.headers += "Location" to "/$token/"
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

    private fun serveUi(exchange: Exchange, path: String) {
        if (!validPath(path)) return send(exchange, 400, "text/plain", "chemin invalide".toByteArray())
        val bytes = WebServer::class.java.getResourceAsStream("/ui/$path")?.use { it.readBytes() }
            ?: return send(exchange, 404, "text/plain", "introuvable : $path".toByteArray())
        send(exchange, 200, contentType(path), bytes)
    }

    private fun servePluginFile(exchange: Exchange, pluginId: String, path: String) {
        if (!validPath(path)) return send(exchange, 400, "text/plain", "chemin invalide".toByteArray())
        val bytes = resource(pluginId, path) ?: return send(exchange, 404, "text/plain", "introuvable : $path".toByteArray())
        send(exchange, 200, contentType(path), bytes)
    }

    private fun appCommand(exchange: Exchange, name: String) {
        val routes = app ?: return send(exchange, 503, "text/plain", "interface indisponible".toByteArray())
        val query = exchange.request.rawQuery.split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        respond(exchange, routes.command(name, exchange.request.body, query))
    }

    private fun pluginCall(exchange: Exchange, pluginId: String, action: String) {
        val body = exchange.request.body.decodeToString().ifBlank { "null" }
        respond(exchange, call(pluginId, action, body))
    }

    private fun respond(exchange: Exchange, result: Result<String?>) = result.fold(
        onSuccess = { json ->
            if (json == null) send(exchange, 204, "application/json", ByteArray(0))
            else send(exchange, 200, "application/json", json.toByteArray())
        },
        onFailure = { send(exchange, 400, "application/json", "{\"error\":${jsonQuote(it.message ?: "erreur")}}".toByteArray()) },
    )

    /** Flux SSE : la connexion reste ouverte (un thread du pool par page ouverte). */
    private fun stream(exchange: Exchange, key: String, initial: List<Pair<String, String>>) {
        val out = exchange.out
        writeHead(exchange, 200, listOf("Content-Type" to "text/event-stream; charset=utf-8", "Cache-Control" to "no-store"), null)
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

    private fun send(exchange: Exchange, status: Int, type: String, bytes: ByteArray) {
        val contentType = if (type.startsWith("text/") || type.endsWith("json")) "$type; charset=utf-8" else type
        writeHead(exchange, status, listOf("Content-Type" to contentType, "Cache-Control" to "no-store") + exchange.headers, bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.out.write(bytes)
    }

    /** Ligne de statut et en-têtes ; [length] null = flux (fermé avec la connexion). */
    private fun writeHead(exchange: Exchange, status: Int, headers: List<Pair<String, String>>, length: Long?) {
        exchange.sent = true
        val head = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "OK"}\r\n")
        headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        if (length != null) head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        exchange.out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        exchange.out.flush()
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

        /** Corps de requête accepté (sons envoyés à la Soundboard, jars). */
        const val MAX_BODY = 512L * 1024 * 1024

        val REASONS = mapOf(200 to "OK", 204 to "No Content", 302 to "Found", 400 to "Bad Request", 404 to "Not Found",
            500 to "Internal Server Error", 503 to "Service Unavailable")

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

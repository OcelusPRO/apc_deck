package fr.ftnl.apcdeck.core.remote

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.PluginLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** État de la connexion du mobile à un PC. */
data class ClientStatus(
    val state: State = State.IDLE,
    /** PC visé (empreinte), null au repos. */
    val server: String? = null,
    val serverName: String? = null,
    val address: String? = null,
    val error: String? = null,
) {
    enum class State { IDLE, CONNECTING, CONNECTED, ERROR }
}

/**
 * Client remote (mobile) : se connecte à un PC appairé et lui sert d'APC ([send]) ; reçoit l'état de ses LED
 * ([onMessage]). Reconnexion automatique tant que la connexion est voulue, sauf si le PC refuse l'appareil.
 */
class RemoteClient(
    private val store: RemoteStore,
    private val deviceName: () -> String,
    private val log: PluginLogger,
    /** Message du PC (thread de la connexion). */
    private val onMessage: (Message) -> Unit,
    /** Connecté (vrai) ou déconnecté (faux), thread quelconque. */
    private val onLink: (Boolean) -> Unit = {},
) {
    private val _status = MutableStateFlow(ClientStatus())
    val status: StateFlow<ClientStatus> = _status

    val servers: List<KnownServer> get() = store.servers()

    /** Connexion voulue en cours (une seule à la fois) ; changer de génération arrête l'ancienne. */
    @Volatile
    private var generation = 0
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var frames: FrameStream? = null

    /** Appaire ce mobile au PC du [link] (QR code scanné ou lien collé), puis reste connecté. */
    fun pair(link: String) {
        val parsed = PairingLink.parse(link)
        start(KnownServer(parsed.fingerprint, parsed.name, parsed.hosts, parsed.port, System.currentTimeMillis()), parsed.secret)
    }

    /** Se connecte à un PC déjà appairé. */
    fun connect(fingerprint: String) {
        val server = store.servers().firstOrNull { it.fingerprint == fingerprint } ?: error("PC inconnu : appaire-le d'abord")
        start(server, null)
    }

    fun disconnect() {
        generation++
        closeSocket(Message.Bye("déconnexion demandée sur le mobile"))
        _status.value = ClientStatus()
        onLink(false)
    }

    /** Oublie un PC (et s'en déconnecte si besoin). */
    fun forget(fingerprint: String) {
        if (_status.value.server == fingerprint) disconnect()
        store.saveServers(store.servers().filter { it.fingerprint != fingerprint })
        _status.value = _status.value.copy()
    }

    /** Envoie une entrée au PC ; ignorée si la connexion n'est pas établie. */
    fun send(event: ApcEvent) = write(Message.Input(event))

    val isConnected: Boolean get() = _status.value.state == ClientStatus.State.CONNECTED

    private fun start(server: KnownServer, secret: ByteArray?) {
        val gen = ++generation
        closeSocket(null)
        onLink(false)
        _status.value = ClientStatus(ClientStatus.State.CONNECTING, server.fingerprint, server.name)
        thread(name = "apc-remote-client", isDaemon = true) { loop(gen, server, secret) }
    }

    private fun loop(gen: Int, initial: KnownServer, pairingSecret: ByteArray?) {
        var server = initial
        var secret = pairingSecret
        var delay = 1_000L
        while (gen == generation) {
            try {
                session(gen, server, secret)
                delay = 1_000L
            } catch (e: RemoteException) {
                // Refus ou mauvais PC : réessayer ne servirait à rien.
                if (gen == generation) {
                    log.warn("connexion à ${server.name} : ${e.message}")
                    _status.value = ClientStatus(ClientStatus.State.ERROR, server.fingerprint, server.name, error = e.message)
                }
                return
            } catch (e: IOException) {
                if (gen != generation) return
                _status.value = ClientStatus(ClientStatus.State.CONNECTING, server.fingerprint, server.name,
                    error = "${server.name} injoignable (${e.message ?: e::class.simpleName}), nouvelle tentative…")
            } finally {
                if (gen == generation) onLink(false)
            }
            // Appairage réussi : les tentatives suivantes sont des reconnexions.
            store.servers().firstOrNull { it.fingerprint == server.fingerprint }?.let {
                server = it
                secret = null
            }
            Thread.sleep(delay)
            delay = (delay * 2).coerceAtMost(10_000L)
        }
    }

    /** Une connexion, jusqu'à sa fin (sa socket est fermée en sortant). */
    private fun session(gen: Int, server: KnownServer, secret: ByteArray?) {
        val (s, host) = open(server)
        try {
            socket = s
            if (gen == generation) connected(gen, s, host, server, secret)
        } finally {
            runCatching { s.close() }
            if (socket === s) socket = null
        }
    }

    private fun connected(gen: Int, s: Socket, host: String, server: KnownServer, secret: ByteArray?) {
        s.soTimeout = 15_000
        val stream = FrameStream(s.getInputStream(), s.getOutputStream())
        val result = Handshake.client(stream, store.identity, deviceName(), server.fingerprint, secret)
        frames = stream
        // Le PC a pu changer de nom ; l'adresse qui a répondu passe en tête.
        val saved = server.copy(name = result.serverName, hosts = listOf(host) + server.hosts.filter { it != host })
        store.saveServers(listOf(saved) + store.servers().filter { it.fingerprint != server.fingerprint })
        if (secret != null) log.info("appairé à ${result.serverName}")
        log.info("connecté à ${result.serverName} ($host)")
        _status.value = ClientStatus(ClientStatus.State.CONNECTED, server.fingerprint, result.serverName, host)
        onLink(true)

        val pinger = thread(name = "apc-remote-ping", isDaemon = true) {
            try {
                while (frames === stream) {
                    Thread.sleep(PING_INTERVAL)
                    stream.write(Message.encode(Message.Ping))
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            }
        }
        try {
            while (gen == generation) {
                when (val message = Message.decode(stream.read())) {
                    is Message.Bye -> {
                        log.info("${result.serverName} a fermé la connexion : ${message.reason}")
                        throw EOFException(message.reason)
                    }
                    Message.Pong, Message.Ping -> {}
                    else -> onMessage(message)
                }
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("le PC ne répond plus")
        } finally {
            if (frames === stream) frames = null
            pinger.interrupt()
        }
    }

    /** Essaie les adresses du PC une à une (la dernière qui a marché en premier). */
    private fun open(server: KnownServer): Pair<Socket, String> {
        var last: IOException? = null
        for (host in server.hosts) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, server.port), CONNECT_TIMEOUT)
                return s to host
            } catch (e: IOException) {
                runCatching { s.close() }
                last = e
            }
        }
        throw last ?: IOException("aucune adresse")
    }

    private fun write(message: Message) {
        val stream = frames ?: return
        try {
            stream.write(Message.encode(message))
        } catch (_: IOException) {
            runCatching { socket?.close() }
        }
    }

    private fun closeSocket(bye: Message.Bye?) {
        bye?.let(::write)
        frames = null
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val CONNECT_TIMEOUT = 3_000
        private const val PING_INTERVAL = 5_000L
    }
}

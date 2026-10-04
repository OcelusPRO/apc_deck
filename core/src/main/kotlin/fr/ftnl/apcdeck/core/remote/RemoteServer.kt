package fr.ftnl.apcdeck.core.remote

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.PadEvent
import fr.ftnl.apcdeck.api.PluginLogger
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.PublicKey
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Appairage en cours : le QR code reste valable jusqu'à [expiresAt] (ms depuis l'époque), pour un seul appareil. */
class Pairing(val link: PairingLink, val expiresAt: Long)

/** Mobile connecté en ce moment. */
data class RemoteSession(val fingerprint: String, val name: String, val address: String, val since: Long)

/**
 * Serveur remote (PC) : écoute sur le réseau local, authentifie les mobiles ([Handshake]) et relaie leurs entrées
 * ([onInput], comme un APC branché) ; leur envoie l'état des LED ([broadcast]). Un appareil inconnu ne peut se
 * connecter qu'avec le secret d'un QR code affiché ([startPairing]).
 */
class RemoteServer(
    private val store: RemoteStore,
    private val serverName: () -> String,
    private val log: PluginLogger,
    /** Entrée d'un mobile (thread de sa connexion). */
    private val onInput: (ApcEvent) -> Unit,
    /** Messages envoyés à un mobile qui vient de se connecter (état complet). */
    private val initial: () -> List<Message>,
    /** Appareils, connexions ou appairage modifiés. */
    private val onChange: () -> Unit,
) {
    private var server: ServerSocket? = null
    private val connections = CopyOnWriteArrayList<Connection>()

    @Volatile
    var pairing: Pairing? = null
        private set

    val isRunning: Boolean get() = server?.isClosed == false
    val port: Int get() = server?.localPort ?: 0
    val sessions: List<RemoteSession> get() = connections.mapNotNull { it.session }
    val devices: List<TrustedDevice> get() = store.devices()

    /** Écoute sur toutes les interfaces ; lève une exception si le port est pris. */
    @Synchronized
    fun start(port: Int) {
        if (isRunning) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port), 16)
        }
        server = socket
        thread(name = "apc-remote-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                if (connections.size >= MAX_CONNECTIONS) {
                    runCatching { client.close() }
                    continue
                }
                val connection = Connection(client)
                connections += connection
                thread(name = "apc-remote-${client.inetAddress.hostAddress}", isDaemon = true) { connection.run() }
            }
        }
        log.info("contrôle à distance : en écoute sur le port ${socket.localPort}")
        onChange()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pairing = null
        connections.forEach { it.close("contrôle à distance désactivé sur le PC") }
        onChange()
    }

    /** Nouveau QR code d'appairage, valable [validityMillis] pour un seul appareil. */
    fun startPairing(validityMillis: Long = PAIRING_VALIDITY): Pairing {
        check(isRunning) { "contrôle à distance désactivé" }
        val link = PairingLink(serverName(), localAddresses(), port, store.identity.fingerprint, RemoteCrypto.randomBytes(16))
        return Pairing(link, System.currentTimeMillis() + validityMillis).also {
            pairing = it
            onChange()
        }
    }

    fun cancelPairing() {
        pairing = null
        onChange()
    }

    /** Retire l'autorisation d'un appareil et le déconnecte. */
    fun revoke(fingerprint: String) {
        val device = store.devices().firstOrNull { it.fingerprint == fingerprint } ?: return
        store.saveDevices(store.devices().filter { it.fingerprint != fingerprint })
        connections.filter { it.session?.fingerprint == fingerprint }.forEach { it.close("autorisation retirée sur le PC") }
        log.info("${device.name} n'est plus autorisé à piloter ce PC")
        onChange()
    }

    /** Envoie [message] à tous les mobiles connectés. */
    fun broadcast(message: Message) {
        if (connections.isEmpty()) return
        val bytes = Message.encode(message)
        connections.forEach { it.send(bytes) }
    }

    private val authorizer = object : Handshake.Authorizer {
        override fun isTrusted(fingerprint: String): Boolean = store.devices().any { it.fingerprint == fingerprint }

        override fun pairingSecret(): ByteArray? = pairing?.takeIf { it.expiresAt > System.currentTimeMillis() }?.link?.secret

        @Synchronized
        override fun paired(fingerprint: String, name: String, key: PublicKey) {
            pairing = null // usage unique
            val now = System.currentTimeMillis()
            store.saveDevices(store.devices().filter { it.fingerprint != fingerprint } +
                TrustedDevice(fingerprint, name, RemoteCrypto.b64(key.encoded), now, now))
            log.info("$name appairé : il peut piloter ce PC")
        }
    }

    private inner class Connection(private val socket: Socket) {
        @Volatile
        var session: RemoteSession? = null
        private var frames: FrameStream? = null

        /** Entrées tenues par ce mobile : relâchées s'il se déconnecte (sinon un pad resterait enfoncé). */
        private val held = LinkedHashMap<String, ApcEvent>()

        fun run() {
            val address = socket.inetAddress.hostAddress ?: "?"
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = HANDSHAKE_TIMEOUT
                val stream = FrameStream(socket.getInputStream(), socket.getOutputStream())
                val result = Handshake.server(stream, store.identity, serverName(), authorizer)
                frames = stream
                touch(result.clientFingerprint)
                session = RemoteSession(result.clientFingerprint, result.deviceName, address, System.currentTimeMillis())
                log.info("${result.deviceName} connecté depuis $address")
                onChange()
                initial().forEach { stream.write(Message.encode(it)) }
                socket.soTimeout = IDLE_TIMEOUT // le mobile envoie un Ping régulièrement
                while (true) {
                    when (val message = Message.decode(stream.read())) {
                        is Message.Input -> {
                            track(message.event)
                            onInput(message.event)
                        }
                        Message.Ping -> stream.write(Message.encode(Message.Pong))
                        is Message.Bye -> break
                        else -> {}
                    }
                }
            } catch (e: RemoteException) {
                log.warn("contrôle à distance ($address) : ${e.message}")
            } catch (_: SocketTimeoutException) {
                session?.let { log.warn("${it.name} ne répond plus") }
            } catch (_: EOFException) {
            } catch (_: IOException) {
            } catch (t: Throwable) {
                log.error("contrôle à distance ($address) : erreur", t)
            } finally {
                runCatching { socket.close() }
                connections.remove(this)
                releaseHeld()
                session?.let {
                    log.info("${it.name} déconnecté")
                    onChange()
                }
            }
        }

        private fun touch(fingerprint: String) {
            val devices = store.devices()
            if (devices.any { it.fingerprint == fingerprint }) {
                store.saveDevices(devices.map { if (it.fingerprint == fingerprint) it.copy(lastSeen = System.currentTimeMillis()) else it })
            }
        }

        private fun track(event: ApcEvent) {
            val key = when (event) {
                is PadEvent -> "p${event.index}"
                is ButtonEvent -> "b${event.button}"
                is KeyEvent -> "k${event.note}"
                else -> return
            }
            synchronized(held) {
                if (pressed(event)) held[key] = event else held.remove(key)
            }
        }

        private fun pressed(event: ApcEvent) = when (event) {
            is PadEvent -> event.pressed
            is ButtonEvent -> event.pressed
            is KeyEvent -> event.pressed
            else -> false
        }

        private fun releaseHeld() {
            val events = synchronized(held) { held.values.toList().also { held.clear() } }
            events.forEach { e ->
                onInput(when (e) {
                    is PadEvent -> e.copy(pressed = false, velocity = 0)
                    is ButtonEvent -> e.copy(pressed = false)
                    is KeyEvent -> e.copy(pressed = false, velocity = 0)
                    else -> return@forEach
                })
            }
        }

        fun send(bytes: ByteArray) {
            val stream = frames ?: return
            try {
                stream.write(bytes)
            } catch (_: IOException) {
                runCatching { socket.close() }
            }
        }

        fun close(reason: String) {
            send(Message.encode(Message.Bye(reason)))
            runCatching { socket.close() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 47810
        const val PAIRING_VALIDITY = 5 * 60_000L
        private const val MAX_CONNECTIONS = 8
        private const val HANDSHAKE_TIMEOUT = 10_000
        private const val IDLE_TIMEOUT = 20_000

        /** Adresses IPv4 de ce PC sur les réseaux locaux (pour le QR code), les plus probables d'abord. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .sortedBy { if (it.isSiteLocalAddress) 0 else 1 }
                .mapNotNull(InetAddress::getHostAddress)
                .distinct()
        }.getOrDefault(emptyList()).ifEmpty { listOf(InetAddress.getLocalHost().hostAddress) }
    }
}

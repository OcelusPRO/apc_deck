package fr.ftnl.apcdeck.core.remote

import fr.ftnl.apcdeck.core.JSON
import fr.ftnl.apcdeck.core.writeAtomic
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Appareil (mobile) autorisé à piloter ce PC. */
@Serializable
data class TrustedDevice(val fingerprint: String, val name: String, val publicKey: String, val pairedAt: Long, val lastSeen: Long = 0)

/** PC appairé, côté mobile : où le joindre et quelle empreinte il doit présenter. */
@Serializable
data class KnownServer(val fingerprint: String, val name: String, val hosts: List<String>, val port: Int, val pairedAt: Long)

@Serializable
private data class IdentityJson(val publicKey: String, val privateKey: String)

/**
 * Fichiers du dossier remote/ : identity.json (clé privée de cette installation, lisible par son seul propriétaire
 * quand le système le permet), devices.json (mobiles autorisés à piloter ce PC), servers.json (PC appairés à ce mobile).
 */
class RemoteStore(home: Path) {
    private val dir: Path = home.resolve("remote").createDirectories()
    private val identityFile = dir.resolve("identity.json")
    private val devicesFile = dir.resolve("devices.json")
    private val serversFile = dir.resolve("servers.json")

    /** Créée au premier besoin. */
    val identity: Identity by lazy { loadIdentity() }

    @Synchronized
    fun devices(): List<TrustedDevice> = read(devicesFile) ?: emptyList()

    @Synchronized
    fun saveDevices(devices: List<TrustedDevice>) = writeAtomic(devicesFile, JSON.encodeToString(devices))

    @Synchronized
    fun servers(): List<KnownServer> = read(serversFile) ?: emptyList()

    @Synchronized
    fun saveServers(servers: List<KnownServer>) = writeAtomic(serversFile, JSON.encodeToString(servers))

    private inline fun <reified T> read(file: Path): T? =
        if (file.exists()) runCatching { JSON.decodeFromString<T>(file.readText()) }.getOrNull() else null

    @Synchronized
    private fun loadIdentity(): Identity {
        read<IdentityJson>(identityFile)?.let { json ->
            runCatching {
                return Identity(KeyPair(RemoteCrypto.publicKey(RemoteCrypto.unb64(json.publicKey)), RemoteCrypto.privateKey(RemoteCrypto.unb64(json.privateKey))))
            }
        }
        val keys = RemoteCrypto.newKeyPair()
        writeAtomic(identityFile, JSON.encodeToString(IdentityJson(RemoteCrypto.b64(keys.public.encoded), RemoteCrypto.b64(keys.private.encoded))))
        runCatching { Files.setPosixFilePermissions(identityFile, PosixFilePermissions.fromString("rw-------")) }
        return Identity(keys)
    }
}

/**
 * Lien d'appairage, contenu du QR code affiché par le PC :
 * `apcdeck://pair?v=1&n=<nom>&h=<adresses séparées par des virgules>&p=<port>&f=<empreinte du PC>&s=<secret>`.
 * Il contient le secret à usage unique : il ne sert qu'une fois et expire vite.
 */
data class PairingLink(val name: String, val hosts: List<String>, val port: Int, val fingerprint: String, val secret: ByteArray) {
    fun format(): String = "apcdeck://pair?v=${Handshake.VERSION}&n=${enc(name)}&h=${enc(hosts.joinToString(","))}&p=$port" +
        "&f=$fingerprint&s=${RemoteCrypto.b64(secret)}"

    companion object {
        private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

        /** Lève une exception explicite si le texte n'est pas un lien d'appairage valide. */
        fun parse(text: String): PairingLink {
            val uri = runCatching { URI(text.trim()) }.getOrNull()
            require(uri != null && uri.scheme == "apcdeck" && uri.rawSchemeSpecificPart.startsWith("//pair")) {
                "ce n'est pas un QR code d'appairage APC Deck"
            }
            val query = uri.rawSchemeSpecificPart.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                val (k, v) = it.split('=', limit = 2)
                k to URLDecoder.decode(v, "UTF-8")
            }
            val version = query["v"]?.toIntOrNull()
            require(version == Handshake.VERSION) { "QR code d'une autre version d'APC Deck (protocole $version) : mets les deux à jour" }
            val hosts = query["h"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            val port = query["p"]?.toIntOrNull()
            val fingerprint = query["f"].orEmpty()
            val secret = runCatching { RemoteCrypto.unb64(query["s"].orEmpty()) }.getOrNull()
            require(hosts.isNotEmpty() && port != null && port in 1..65535 && fingerprint.length == 43 && secret != null && secret.size >= 16) {
                "QR code d'appairage incomplet"
            }
            return PairingLink(query["n"].orEmpty().ifBlank { "PC" }, hosts, port, fingerprint, secret)
        }
    }
}

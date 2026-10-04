package fr.ftnl.apcdeck.core.remote

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.Grid
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.KnobEvent
import fr.ftnl.apcdeck.api.LedState
import fr.ftnl.apcdeck.api.PadEvent
import fr.ftnl.apcdeck.core.InputState
import fr.ftnl.apcdeck.core.LedSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.PublicKey

/**
 * Protocole remote v1 : un mobile (client) sert d'APC à un PC (serveur) sur le réseau local.
 *
 * Poignée de main (après elle, tout est chiffré : voir [FrameStream]) :
 *   1. C -> S  ClientHello : "APCR", version, clé éphémère du client, nonce
 *   2. S -> C  ServerHello : clé d'identité du serveur, clé éphémère du serveur, nonce, nom, signature de l'empreinte
 *              de la conversation par la clé d'identité. Le client vérifie que la clé d'identité correspond à
 *              l'empreinte épinglée (lue dans le QR code à l'appairage) et la signature : un intermédiaire ne peut pas
 *              se faire passer pour le PC.
 *      Clés de session : HKDF(ECDH(éphémères), sel = empreinte de la conversation) ; une clé par direction (secret
 *      persistant même si une clé d'identité fuit plus tard).
 *   3. C -> S  ClientAuth (chiffré) : mode, clé d'identité du client, nom de l'appareil, signature de la conversation,
 *              et à l'appairage une preuve HMAC(secret du QR code, conversation). Le secret ne circule jamais : la
 *              preuve, liée à cette conversation, ne peut pas être rejouée. Le secret est à usage unique et expire.
 *   4. S -> C  AuthResult (chiffré) : accepté, ou la raison du refus.
 * Ensuite : messages [Message] dans les deux sens.
 */
object Handshake {
    private const val MAGIC = "APCR"
    const val VERSION = 1

    private val SERVER_CONTEXT = "APCR-v1 server".toByteArray()
    private val CLIENT_CONTEXT = "APCR-v1 client".toByteArray()
    private val PAIR_CONTEXT = "APCR-v1 pair".toByteArray()
    private val KEYS_CONTEXT = "APCR-v1 keys".toByteArray()

    private const val MODE_PAIR = 1
    private const val MODE_RESUME = 2

    enum class Status(val code: Int, val message: String) {
        OK(0, "accepté"),
        NOT_PAIRED(1, "cet appareil n'est pas (ou plus) appairé à ce PC : scanne à nouveau son QR code"),
        BAD_PAIRING(2, "code d'appairage invalide ou expiré : affiche un nouveau QR code sur le PC"),
        REFUSED(3, "connexion refusée par le PC");

        companion object {
            fun of(code: Int): Status = entries.firstOrNull { it.code == code } ?: REFUSED
        }
    }

    /** Ce que le serveur sait des appareils (appairés, appairage en cours). */
    interface Authorizer {
        fun isTrusted(fingerprint: String): Boolean

        /** Secret du QR code affiché, null si aucun appairage n'est en cours (ou s'il a expiré). */
        fun pairingSecret(): ByteArray?

        /** Appairage réussi : retenir l'appareil et invalider le secret. */
        fun paired(fingerprint: String, name: String, key: PublicKey)
    }

    class ClientResult(val serverName: String, val serverFingerprint: String)
    class ServerResult(val clientFingerprint: String, val deviceName: String, val paired: Boolean)

    /**
     * Côté mobile. [pairingSecret] non nul : premier appairage ; sinon reconnexion d'un appareil déjà appairé.
     * Lève [RemoteException] si le serveur n'est pas celui attendu ou refuse.
     */
    fun client(frames: FrameStream, identity: Identity, deviceName: String, serverFingerprint: String, pairingSecret: ByteArray?): ClientResult {
        val ephemeral = RemoteCrypto.newKeyPair()
        val hello = encode {
            writeUTF(MAGIC)
            writeByte(VERSION)
            bytes(ephemeral.public.encoded)
            bytes(RemoteCrypto.randomBytes(32))
        }
        frames.write(hello)

        val serverHello = frames.read()
        val (unsigned, serverKey, serverEphemeral, serverName, signature) = decode(serverHello) {
            val serverKey = RemoteCrypto.publicKey(bytes())
            val serverEphemeral = RemoteCrypto.publicKey(bytes())
            bytes() // nonce
            val name = readUTF()
            val unsignedLength = serverHello.size - available()
            ServerHelloParts(serverHello.copyOf(unsignedLength), serverKey, serverEphemeral, name, bytes())
        }
        if (RemoteCrypto.fingerprint(serverKey) != serverFingerprint) {
            throw RemoteException("ce n'est pas le PC appairé (empreinte différente) : connexion interrompue")
        }
        val transcript = RemoteCrypto.sha256(hello, unsigned)
        if (!RemoteCrypto.verify(serverKey, SERVER_CONTEXT + transcript, signature)) throw RemoteException("signature du PC invalide")
        startEncryption(frames, RemoteCrypto.ecdh(ephemeral.private, serverEphemeral), transcript, client = true)

        frames.write(encode {
            writeByte(if (pairingSecret != null) MODE_PAIR else MODE_RESUME)
            bytes(identity.publicKey.encoded)
            writeUTF(deviceName.take(64))
            bytes(identity.sign(CLIENT_CONTEXT + transcript))
            bytes(pairingSecret?.let { RemoteCrypto.hmac(it, PAIR_CONTEXT, transcript, identity.publicKey.encoded) } ?: ByteArray(0))
        })
        val status = decode(frames.read()) { Status.of(readUnsignedByte()) }
        if (status != Status.OK) throw RemoteException(status.message)
        return ClientResult(serverName, serverFingerprint)
    }

    private data class ServerHelloParts(
        val unsigned: ByteArray, val serverKey: PublicKey, val serverEphemeral: PublicKey, val name: String, val signature: ByteArray,
    )

    /** Côté PC. Lève [RemoteException] (après avoir prévenu le client) si l'appareil n'est pas autorisé. */
    fun server(frames: FrameStream, identity: Identity, serverName: String, authorizer: Authorizer): ServerResult {
        val hello = frames.read()
        val clientEphemeral = decode(hello) {
            if (readUTF() != MAGIC) throw RemoteException("ce n'est pas un client APC Deck")
            val version = readUnsignedByte()
            if (version != VERSION) throw RemoteException("version du protocole non prise en charge ($version)")
            RemoteCrypto.publicKey(bytes())
        }
        val ephemeral = RemoteCrypto.newKeyPair()
        val unsigned = encode {
            bytes(identity.publicKey.encoded)
            bytes(ephemeral.public.encoded)
            bytes(RemoteCrypto.randomBytes(32))
            writeUTF(serverName.take(64))
        }
        val transcript = RemoteCrypto.sha256(hello, unsigned)
        frames.write(unsigned + encode { bytes(identity.sign(SERVER_CONTEXT + transcript)) })
        startEncryption(frames, RemoteCrypto.ecdh(ephemeral.private, clientEphemeral), transcript, client = false)

        val auth = frames.read()
        val (status, result) = decode(auth) {
            val mode = readUnsignedByte()
            val clientKeyBytes = bytes()
            val clientKey = RemoteCrypto.publicKey(clientKeyBytes)
            val name = readUTF().ifBlank { "mobile" }
            val signature = bytes()
            val proof = bytes()
            val fingerprint = RemoteCrypto.fingerprint(clientKey)
            val result = ServerResult(fingerprint, name, mode == MODE_PAIR)
            val status = when {
                !RemoteCrypto.verify(clientKey, CLIENT_CONTEXT + transcript, signature) -> Status.REFUSED
                mode == MODE_PAIR -> {
                    val secret = authorizer.pairingSecret()
                    if (secret != null && RemoteCrypto.same(proof, RemoteCrypto.hmac(secret, PAIR_CONTEXT, transcript, clientKeyBytes))) {
                        authorizer.paired(fingerprint, name, clientKey)
                        Status.OK
                    } else Status.BAD_PAIRING
                }
                mode == MODE_RESUME && authorizer.isTrusted(fingerprint) -> Status.OK
                mode == MODE_RESUME -> Status.NOT_PAIRED
                else -> Status.REFUSED
            }
            status to result
        }
        frames.write(byteArrayOf(status.code.toByte()))
        if (status != Status.OK) throw RemoteException("${result.deviceName} (${result.clientFingerprint.take(8)}) refusé : ${status.message}")
        return result
    }

    private fun startEncryption(frames: FrameStream, shared: ByteArray, transcript: ByteArray, client: Boolean) {
        val keys = RemoteCrypto.hkdf(shared, transcript, KEYS_CONTEXT, 64)
        val toServer = keys.copyOfRange(0, 32)
        val toClient = keys.copyOfRange(32, 64)
        if (client) frames.encrypt(send = toServer, receive = toClient) else frames.encrypt(send = toClient, receive = toServer)
    }
}

/** Messages échangés une fois la connexion authentifiée. */
sealed interface Message {
    /** Mobile -> PC : une entrée de l'APC (virtuel ou réel branché au mobile). */
    data class Input(val event: ApcEvent) : Message

    /** PC -> mobile : état des LED à afficher. */
    data class Leds(val snapshot: LedSnapshot) : Message

    /** PC -> mobile : entrées enfoncées et potars (ceux du PC et des autres appareils compris). */
    data class State(val input: InputState) : Message

    data object Ping : Message
    data object Pong : Message

    /** Fermeture volontaire, avec sa raison. */
    data class Bye(val reason: String) : Message

    companion object {
        private const val INPUT = 1
        private const val LEDS = 2
        private const val STATE = 3
        private const val PING = 4
        private const val PONG = 5
        private const val BYE = 6

        fun encode(message: Message): ByteArray = encode {
            when (message) {
                is Input -> {
                    writeByte(INPUT)
                    when (val e = message.event) {
                        is PadEvent -> { writeByte(0); writeByte(e.x); writeByte(e.y); writeBoolean(e.pressed); writeByte(e.velocity) }
                        is ButtonEvent -> { writeByte(1); writeUTF(e.button.name); writeBoolean(e.pressed) }
                        is KeyEvent -> { writeByte(2); writeByte(e.note); writeBoolean(e.pressed); writeByte(e.velocity) }
                        is KnobEvent -> { writeByte(3); writeByte(e.index); writeShort(e.delta); writeByte(e.value) }
                    }
                }
                is Leds -> {
                    writeByte(LEDS)
                    message.snapshot.padColors.forEach { writeByte(it) }
                    message.snapshot.padEffects.forEach { writeByte(it.channel) }
                    writeByte(message.snapshot.buttons.size)
                    message.snapshot.buttons.forEach { (button, state) -> writeUTF(button.name); writeByte(state.velocity) }
                }
                is State -> {
                    writeByte(STATE)
                    val i = message.input
                    writeByte(i.pads.size); i.pads.forEach { writeByte(it) }
                    writeByte(i.buttons.size); i.buttons.forEach { writeUTF(it.name) }
                    writeByte(i.notes.size); i.notes.forEach { writeByte(it) }
                    writeByte(i.knobs.size); i.knobs.forEach { writeByte(it) }
                }
                Ping -> writeByte(PING)
                Pong -> writeByte(PONG)
                is Bye -> { writeByte(BYE); writeUTF(message.reason.take(200)) }
            }
        }

        /** Lève [RemoteException] pour un message invalide. */
        fun decode(bytes: ByteArray): Message = try {
            decode(bytes) {
                when (readUnsignedByte()) {
                    INPUT -> Input(
                        when (readUnsignedByte()) {
                            0 -> PadEvent(readUnsignedByte().coerceIn(0, Grid.COLS - 1), readUnsignedByte().coerceIn(0, Grid.ROWS - 1),
                                readBoolean(), readUnsignedByte().coerceIn(0, 127))
                            1 -> ButtonEvent(Button.valueOf(readUTF()), readBoolean())
                            2 -> KeyEvent(readUnsignedByte().coerceIn(0, 127), readBoolean(), readUnsignedByte().coerceIn(0, 127))
                            3 -> KnobEvent(readUnsignedByte().coerceIn(0, 7), readShort().toInt(), readUnsignedByte().coerceIn(0, 127))
                            else -> throw RemoteException("entrée inconnue")
                        },
                    )
                    LEDS -> {
                        val colors = List(Grid.PADS) { readUnsignedByte() and 0x7F }
                        val effects = List(Grid.PADS) { Effect.ofChannel(readUnsignedByte() and 0x0F) }
                        val buttons = List(readUnsignedByte()) {
                            Button.valueOf(readUTF()) to readUnsignedByte().let { v -> LedState.entries.first { it.velocity == v } }
                        }.toMap()
                        Leds(LedSnapshot(colors, effects, buttons))
                    }
                    STATE -> State(
                        InputState(
                            pads = List(readUnsignedByte()) { readUnsignedByte() }.toSet(),
                            buttons = List(readUnsignedByte()) { Button.valueOf(readUTF()) }.toSet(),
                            notes = List(readUnsignedByte()) { readUnsignedByte() }.toSet(),
                            knobs = List(readUnsignedByte()) { readUnsignedByte() },
                        ),
                    )
                    PING -> Ping
                    PONG -> Pong
                    BYE -> Bye(readUTF())
                    else -> throw RemoteException("message inconnu")
                }
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("message invalide : ${e.message}")
        }
    }
}

// --- encodage binaire ------------------------------------------------------------------------------

internal fun encode(block: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { DataOutputStream(it).apply(block).flush() }.toByteArray()

internal fun <T> decode(bytes: ByteArray, block: DataInputStream.() -> T): T = try {
    DataInputStream(ByteArrayInputStream(bytes)).block()
} catch (e: RemoteException) {
    throw e
} catch (e: IOException) {
    throw RemoteException("message tronqué")
}

/** Tableau d'octets précédé de sa longueur (2 octets). */
internal fun DataOutputStream.bytes(value: ByteArray) {
    writeShort(value.size)
    write(value)
}

internal fun DataInputStream.bytes(): ByteArray = ByteArray(readUnsignedShort()).also(::readFully)

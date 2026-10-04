package fr.ftnl.apcdeck.core.remote

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives du protocole remote, toutes disponibles sur le JDK comme sur Android (API 26) :
 * courbe P-256 (ECDH pour l'échange de clés, ECDSA pour l'authentification), SHA-256, HMAC, HKDF, AES-256-GCM.
 */
object RemoteCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun publicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    fun privateKey(encoded: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

    /** Empreinte d'une clé publique : SHA-256 de son encodage X.509, en base64url (43 caractères). */
    fun fingerprint(key: PublicKey): String = b64(sha256(key.encoded))

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }

    fun verify(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(signature) }
    }.getOrDefault(false)

    fun ecdh(own: PrivateKey, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(own); doPhase(peer, true); generateSecret() }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach(::update); doFinal() }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block, info, byteArrayOf(counter++.toByte()))
            out.write(block)
        }
        return out.toByteArray().copyOf(length)
    }

    /** Comparaison en temps constant. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

/** Paire de clés d'identité d'une installation (PC ou mobile), conservée d'un lancement à l'autre. */
class Identity(val keys: KeyPair) {
    val publicKey: PublicKey get() = keys.public
    val fingerprint: String = RemoteCrypto.fingerprint(keys.public)

    fun sign(data: ByteArray): ByteArray = RemoteCrypto.sign(keys.private, data)
}

/** Erreur de protocole ou d'authentification : la connexion est fermée. */
class RemoteException(message: String) : IOException(message)

/**
 * Trames : longueur sur 4 octets (gros-boutiste) puis contenu. Avant l'échange de clés le contenu est en clair ; après,
 * chaque trame est chiffrée et authentifiée (AES-256-GCM, nonce = compteur de la direction : une trame rejouée,
 * réordonnée ou modifiée est refusée et ferme la connexion).
 */
class FrameStream(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input.buffered())
    private val output = DataOutputStream(output.buffered())
    private var sendKey: SecretKeySpec? = null
    private var receiveKey: SecretKeySpec? = null
    private var sendCounter = 0L
    private var receiveCounter = 0L

    /** Passe en mode chiffré, avec une clé par direction. */
    fun encrypt(send: ByteArray, receive: ByteArray) {
        sendKey = SecretKeySpec(send, "AES")
        receiveKey = SecretKeySpec(receive, "AES")
    }

    @Synchronized
    fun write(payload: ByteArray) {
        val key = sendKey
        val frame = if (key == null) payload else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(sendCounter++)))
            doFinal(payload)
        }
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    /** Lit une trame ; lève [EOFException] à la fermeture, [RemoteException] si elle est invalide. */
    fun read(): ByteArray {
        val size = input.readInt()
        if (size !in 0..MAX_FRAME) throw RemoteException("trame de taille invalide ($size)")
        val frame = ByteArray(size).also(input::readFully)
        val key = receiveKey ?: return frame
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(receiveCounter++)))
                doFinal(frame)
            }
        } catch (t: Exception) {
            throw RemoteException("trame refusée (altérée ou rejouée)")
        }
    }

    private fun nonce(counter: Long): ByteArray = ByteArray(12).also { n ->
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
    }

    companion object {
        const val MAX_FRAME = 1 shl 20
    }
}

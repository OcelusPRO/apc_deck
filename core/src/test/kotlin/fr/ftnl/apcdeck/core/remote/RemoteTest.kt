package fr.ftnl.apcdeck.core.remote

import fr.ftnl.apcdeck.api.ApcEvent
import fr.ftnl.apcdeck.api.Button
import fr.ftnl.apcdeck.api.ButtonEvent
import fr.ftnl.apcdeck.api.Effect
import fr.ftnl.apcdeck.api.KeyEvent
import fr.ftnl.apcdeck.api.KnobEvent
import fr.ftnl.apcdeck.api.LedState
import fr.ftnl.apcdeck.api.PadEvent
import fr.ftnl.apcdeck.api.PluginLogger
import fr.ftnl.apcdeck.core.InputState
import fr.ftnl.apcdeck.core.LedSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteTest {
    private val dirs = mutableListOf<Path>()
    private val logs = LinkedBlockingQueue<String>()
    private val log = object : PluginLogger {
        override fun debug(message: String) {}
        override fun info(message: String) { logs.add(message) }
        override fun warn(message: String) { logs.add(message) }
        override fun error(message: String, throwable: Throwable?) { logs.add(message) }
    }

    private fun home(): Path = Files.createTempDirectory("apc-remote").also { dirs.add(it) }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private val leds = LedSnapshot(List(40) { it % 8 }, List(40) { Effect.ofChannel(it % 16) }, mapOf(Button.TRACK_1 to LedState.ON, Button.SCENE_2 to LedState.BLINK))

    private inner class Pc {
        val store = RemoteStore(home())
        val inputs = LinkedBlockingQueue<ApcEvent>()
        val server = RemoteServer(store, { "PC test" }, log, { inputs.add(it) }, { listOf(Message.Leds(leds)) }, {}).apply { start(0) }

        fun link(): PairingLink = server.startPairing().link.copy(hosts = listOf("127.0.0.1"))
    }

    private inner class Phone(val store: RemoteStore = RemoteStore(home())) {
        val messages = LinkedBlockingQueue<Message>()
        val client = RemoteClient(store, { "Téléphone" }, log, { messages.add(it) })

        fun awaitState(state: ClientStatus.State, seconds: Long = 10): ClientStatus {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < end) {
                if (client.status.value.state == state) return client.status.value
                Thread.sleep(20)
            }
            error("état ${client.status.value} au lieu de $state")
        }
    }

    @Test
    fun `appairage puis reconnexion, les entrees et les LED circulent`() {
        val pc = Pc()
        val phone = Phone()
        phone.client.pair(pc.link().format())
        assertEquals("PC test", phone.awaitState(ClientStatus.State.CONNECTED).serverName)
        assertEquals(leds, (phone.messages.poll(5, TimeUnit.SECONDS) as Message.Leds).snapshot) // état initial

        phone.client.send(PadEvent(3, 2, true, 100))
        assertEquals(PadEvent(3, 2, true, 100), pc.inputs.poll(5, TimeUnit.SECONDS))
        pc.server.broadcast(Message.State(InputState(pads = setOf(19))))
        assertEquals(setOf(19), (phone.messages.poll(5, TimeUnit.SECONDS) as Message.State).input.pads)

        // Appareil retenu des deux côtés, secret consommé.
        assertEquals(listOf("Téléphone"), pc.server.devices.map { it.name })
        assertNull(pc.server.pairing)
        assertEquals(listOf(pc.store.identity.fingerprint), phone.store.servers().map { it.fingerprint })

        // Reconnexion sans QR code.
        phone.client.disconnect()
        assertEquals(PadEvent(3, 2, false, 0), pc.inputs.poll(5, TimeUnit.SECONDS)) // pad tenu relâché à la déconnexion
        phone.client.connect(pc.store.identity.fingerprint)
        phone.awaitState(ClientStatus.State.CONNECTED)
        phone.client.send(KnobEvent(2, -3, 61))
        assertEquals(KnobEvent(2, -3, 61), pc.inputs.poll(5, TimeUnit.SECONDS))
        phone.client.disconnect()
        pc.server.stop()
    }

    @Test
    fun `mauvais secret refuse, appareil non retenu`() {
        val pc = Pc()
        val phone = Phone()
        val link = pc.link().copy(secret = RemoteCrypto.randomBytes(16))
        phone.client.pair(link.format())
        val status = phone.awaitState(ClientStatus.State.ERROR)
        assertTrue(status.error!!.contains("invalide ou expiré"), status.error)
        assertTrue(pc.server.devices.isEmpty())
        assertNotNull(pc.server.pairing) // le vrai QR code reste valable
        pc.server.stop()
    }

    @Test
    fun `secret expire refuse`() {
        val pc = Pc()
        val link = pc.server.startPairing(validityMillis = -1).link.copy(hosts = listOf("127.0.0.1"))
        val phone = Phone()
        phone.client.pair(link.format())
        phone.awaitState(ClientStatus.State.ERROR)
        assertTrue(pc.server.devices.isEmpty())
        pc.server.stop()
    }

    @Test
    fun `le secret ne sert qu'une fois`() {
        val pc = Pc()
        val link = pc.link().format()
        Phone().apply { client.pair(link); awaitState(ClientStatus.State.CONNECTED); client.disconnect() }
        val intruder = Phone()
        intruder.client.pair(link)
        intruder.awaitState(ClientStatus.State.ERROR)
        assertEquals(1, pc.server.devices.size)
        pc.server.stop()
    }

    @Test
    fun `un autre PC avec la meme adresse est refuse (empreinte epinglee)`() {
        val pc = Pc()
        val impostor = Pc()
        val phone = Phone()
        // QR code du vrai PC, mais l'adresse mène à un autre (par exemple un intermédiaire sur le réseau).
        val link = pc.link().copy(hosts = listOf("127.0.0.1"))
        val redirected = PairingLink(link.name, link.hosts, impostor.server.port, link.fingerprint, link.secret)
        impostor.server.startPairing()
        phone.client.pair(redirected.format())
        val status = phone.awaitState(ClientStatus.State.ERROR)
        assertTrue(status.error!!.contains("empreinte"), status.error)
        assertTrue(impostor.server.devices.isEmpty())
        pc.server.stop()
        impostor.server.stop()
    }

    @Test
    fun `appareil revoque refuse a la reconnexion`() {
        val pc = Pc()
        val phone = Phone()
        phone.client.pair(pc.link().format())
        phone.awaitState(ClientStatus.State.CONNECTED)
        pc.server.revoke(pc.server.devices.single().fingerprint)
        // Déconnecté par le PC : la reconnexion automatique est refusée.
        val status = phone.awaitState(ClientStatus.State.ERROR)
        assertTrue(status.error!!.contains("plus) appairé"), status.error)
        pc.server.stop()
    }

    @Test
    fun `trame alteree ou rejouee refusee`() {
        val key1 = RemoteCrypto.randomBytes(32)
        val key2 = RemoteCrypto.randomBytes(32)
        val wire = ByteArrayOutputStream()
        FrameStream(ByteArrayInputStream(ByteArray(0)), wire).apply {
            encrypt(key1, key2)
            write("un".toByteArray())
            write("deux".toByteArray())
        }
        val bytes = wire.toByteArray()
        fun reader(data: ByteArray) = FrameStream(ByteArrayInputStream(data), ByteArrayOutputStream()).apply { encrypt(key2, key1) }

        reader(bytes).run {
            assertContentEquals("un".toByteArray(), read())
            assertContentEquals("deux".toByteArray(), read())
        }
        val tampered = bytes.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertFailsWith<RemoteException> { reader(tampered).read() }
        val first = bytes.copyOf(4 + 2 + 16)
        assertFailsWith<RemoteException> { reader(first + first).run { read(); read() } } // même trame rejouée
    }

    @Test
    fun `messages encodes puis decodes a l'identique`() {
        val messages = listOf(
            Message.Input(PadEvent(7, 4, true, 127)),
            Message.Input(ButtonEvent(Button.SHIFT, false)),
            Message.Input(KeyEvent(60, true, 90)),
            Message.Input(KnobEvent(7, -12, 0)),
            Message.Leds(leds),
            Message.State(InputState(setOf(1, 39), setOf(Button.PLAY), setOf(48, 72), List(8) { it * 10 })),
            Message.Ping, Message.Pong, Message.Bye("fin"),
        )
        messages.forEach { assertEquals(it, Message.decode(Message.encode(it))) }
        assertFailsWith<RemoteException> { Message.decode(byteArrayOf(99)) }
        assertFailsWith<RemoteException> { Message.decode(byteArrayOf(1, 1)) }
    }

    @Test
    fun `lien d'appairage`() {
        val link = PairingLink("PC de Léa", listOf("192.168.1.20", "10.0.0.5"), 47810, RemoteCrypto.b64(ByteArray(32)), ByteArray(16) { it.toByte() })
        val parsed = PairingLink.parse(link.format())
        assertEquals(link.name, parsed.name)
        assertEquals(link.hosts, parsed.hosts)
        assertEquals(link.port, parsed.port)
        assertEquals(link.fingerprint, parsed.fingerprint)
        assertContentEquals(link.secret, parsed.secret)
        assertFailsWith<IllegalArgumentException> { PairingLink.parse("https://exemple.fr") }
        assertFailsWith<IllegalArgumentException> { PairingLink.parse("apcdeck://pair?v=1&h=1.2.3.4") }
    }

    @Test
    fun `identite conservee d'un lancement a l'autre`() {
        val dir = home()
        val first = RemoteStore(dir).identity.fingerprint
        assertEquals(first, RemoteStore(dir).identity.fingerprint)
    }
}

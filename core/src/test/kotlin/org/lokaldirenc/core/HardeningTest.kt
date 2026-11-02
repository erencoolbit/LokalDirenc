package org.lokaldirenc.core

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ağ saldırısı incelemesinde bulunan açıkların regresyon testleri. */
class HardeningTest {
    private class MemoryStore : StateStore {
        var data: ByteArray? = null
        override fun load() = data
        override fun save(data: ByteArray) {
            this.data = data
        }
        override fun destroy() {
            data = null
        }
        override val protection = "test"
    }

    private val root: File = Files.createTempDirectory("ld-hard").toFile()
    private val nodes = ArrayList<Node>()
    private val sockets = ArrayList<Socket>()

    private fun freePort() = DatagramSocket(0).use { it.localPort }

    private fun node(name: String, port: Int = freePort(), sees: List<Int> = emptyList()): Node {
        val n = Node(
            File(root, name), MemoryStore(),
            NodeConfig(
                discoveryPort = port, beaconIntervalMs = 80, peerTtlMs = 1500, handshakeTimeoutMs = 1200, ioTimeoutMs = 2000,
                promptTimeoutMs = 2000, meshIntervalMs = 80, meshResyncMs = 300, meshFlushMs = 100,
                extraBeaconTargets = sees.map { InetSocketAddress("127.0.0.1", it) },
            ),
        )
        n.setNick(name)
        n.start()
        nodes += n
        return n
    }

    @AfterTest
    fun cleanup() {
        sockets.forEach { runCatching { it.close() } }
        nodes.forEach { it.stop() }
    }

    private fun waitFor(what: String, timeoutMs: Long = 8000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("Zaman aşımı: $what")
    }

    private fun approve(n: Node) {
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                n.transfers().forEach { t ->
                    if (t.needsPairing) n.confirmPairing(t.id, true)
                    if (t.needsAccept) n.respondOffer(t.id, true)
                }
                runCatching { Thread.sleep(5) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun connectFrom(source: String, port: Int): Socket {
        val s = Socket()
        s.bind(InetSocketAddress(InetAddress.getByName(source), 0))
        s.connect(InetSocketAddress("127.0.0.1", port), 1000)
        s.soTimeout = 5000
        sockets += s
        return s
    }

    @Test
    fun silentConnectionsWithoutCodeDoNotBlockMembers() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("n")
        b.joinNetwork(net.code, "n")
        approve(a)
        approve(b)
        val port = b.networks().single().port
        // Eski davranışta 8 boş bağlantı bütün oturum haklarını tüketiyordu.
        repeat(Node.MAX_HANDSHAKES + 10) { i -> runCatching { connectFrom("127.0.0.${2 + i % 8}", port) } }
        val file = File(a.outbox, "x.txt").also { it.parentFile.mkdirs(); it.writeText("merhaba") }
        val id = a.send(net.id, "127.0.0.1", port, null, file, "x.txt")
        // Toplam sınır dolu olabilir: kesin süre boş bağlantıları düşürür, yeniden deneme geçer.
        waitFor("ilk deneme biter") { a.transfers().first { it.id == id }.status.isFinal }
        if (a.transfers().first { it.id == id }.status != TransferStatus.DONE) {
            Thread.sleep(1500)
            a.retry(id)
            waitFor("yeniden deneme") { a.transfers().first { it.id == id }.status == TransferStatus.DONE }
        }
        assertEquals(TransferStatus.DONE, a.transfers().first { it.id == id }.status)
    }

    @Test
    fun oneSourceCannotUseAllHandshakeSlots() {
        val b = node("b")
        val net = b.createNetwork("n")
        val port = b.networks().single().port
        val held = List(Node.MAX_HANDSHAKES_PER_SOURCE) { connectFrom("127.0.0.2", port) }
        Thread.sleep(100)
        val extra = connectFrom("127.0.0.2", port)
        assertEquals(-1, extra.getInputStream().read(), "sınırı aşan bağlantı hemen kapanmalı")
        assertEquals(net.id, b.networks().single().id)
        assertEquals(Node.MAX_HANDSHAKES_PER_SOURCE, held.size)
    }

    @Test
    fun tricklingHandshakeHitsAbsoluteDeadline() {
        val b = node("b")
        b.createNetwork("n")
        val s = connectFrom("127.0.0.3", b.networks().single().port)
        val started = System.currentTimeMillis()
        val out = s.getOutputStream()
        // Okuma zaman aşımı her baytta yenilenir; yalnız o olsaydı bu döngü dakikalar sürerdi.
        val closed = runCatching {
            out.write(0)
            out.write(200)
            repeat(40) {
                out.write(1)
                out.flush()
                Thread.sleep(100)
            }
        }.isFailure
        val readEnd = runCatching { s.getInputStream().read() }.getOrDefault(-1)
        assertTrue(closed || readEnd == -1, "bağlantı kapatılmalıydı")
        assertTrue(System.currentTimeMillis() - started < 4500)
    }

    @Test
    fun replayedBeaconCannotHijackVerifiedAddress() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa))
        val net = a.createNetwork("n")
        b.joinNetwork(net.code, "n")
        waitFor("kişi doğrulandı") { a.meshContacts(net.id).any { it.nick == "b" && it.proven } }
        Thread.sleep(500) // A'nın kendi başlattığı eşitleme adresi doğrulasın
        val real = a.peers(net.id).single()
        assertEquals("127.0.0.1", real.host)

        // Saldırgan kodu bilmez ama B'nin yayınını kaydedip kendi adresinden tekrarlar.
        val recorded = Beacon.seal(NetCode.discoveryKey(net.code), Beacon.Payload(real.port, real.fingerprint!!, "b"))
        val attacker = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.9"), 0))
        attacker.use { socket ->
            repeat(30) {
                socket.send(DatagramPacket(recorded, recorded.size, InetSocketAddress("127.0.0.1", pa)))
                Thread.sleep(20)
            }
        }
        val after = a.peers(net.id).single()
        assertEquals("127.0.0.1", after.host, "liste sahte adrese geçmemeli")
        a.sendPublicMessage(net.id, "hala ulasiyor")
        waitFor("mesaj gerçek adrese gitti") { b.meshMessages(net.id).any { it.text == "hala ulasiyor" } }
    }

    @Test
    fun oversizedChannelMessageIsRefusedWithoutBufferingIt() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("n")
        b.joinNetwork(net.code, "n")
        approve(b)
        val key = a.snapshot().networks.single()
        val s = Socket("127.0.0.1", b.networks().single().port).apply { soTimeout = 3000 }
        sockets += s
        val ch = SecureChannel.handshake(s, true, key.staticKey.unhex(), NetCode.psk(net.code), NetCode.networkId(net.code))
        ch.send(Msg.Hello(PROTOCOL_VERSION, "kotu"))
        ch.receiveControl()
        ch.send(Msg.PairOk)
        ch.receiveControl()
        assertFailsWith<Exception> {
            ch.sendChunk(0, ByteArray(6 * 1024 * 1024))
            ch.receive()
        }
        waitFor("alıcı oturumu kapattı") { b.transfers().all { it.status.isFinal } }
    }

    @Test
    fun promptFloodIsBounded() {
        val a = node("a")
        val b = node("b")
        val net = a.createNetwork("n")
        b.joinNetwork(net.code, "n")
        val port = b.networks().single().port
        val key = a.snapshot().networks.single()
        // Kimse yanıtlamıyor: istemler ekranda birikmeye çalışır.
        repeat(Node.MAX_PROMPTS + 3) {
            val s = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
            sockets += s
            val ch = SecureChannel.handshake(s, true, key.staticKey.unhex(), NetCode.psk(net.code), NetCode.networkId(net.code))
            ch.send(Msg.Hello(PROTOCOL_VERSION, "sel"))
            ch.receiveControl()
        }
        Thread.sleep(300)
        assertTrue(b.transfers().count { it.needsPairing } <= Node.MAX_PROMPTS)
    }

    @Test
    fun historyStaysBoundedUnderRepeatedFailures() {
        val a = node("a")
        val net = a.createNetwork("n")
        val closed = DatagramSocket(0).use { it.localPort }
        val file = File(a.outbox, "x.txt").also { it.parentFile.mkdirs(); it.writeText("x") }
        val ids = List(Node.MAX_HISTORY + 40) { a.send(net.id, "127.0.0.1", closed, null, file, "x.txt") }
        waitFor("hepsi bitti", 60_000) { a.transfers().count { !it.status.isFinal } == 0 }
        assertTrue(a.snapshot().history.size <= Node.MAX_HISTORY)
        assertTrue(a.transfers().size <= Node.MAX_HISTORY)
        assertEquals(Node.MAX_HISTORY + 40, ids.size)
    }

    @Test
    fun fakeIdentityFloodCannotGrowContactsOrEvictProven() {
        val code = NetCode.generate()
        val netId = NetCode.networkId(code)
        val id = netId.hex()
        val victim = MeshEngine({ false })
        victim.register(id, netId, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "kurban")
        val friend = MeshEngine({ false })
        val friendBox = Noise.generatePrivate()
        friend.register(id, netId, MeshEngine.generateSigningKey(), friendBox, "dost")
        victim.acceptLinkPresence(id, friend.selfPresence(id), sha256(Noise.publicOf(friendBox)).hex())
        assertTrue(victim.contacts(id).single().proven)
        repeat(MeshEngine.MAX_CONTACTS + 50) {
            val fake = MeshEngine({ false })
            fake.register(id, netId, MeshEngine.generateSigningKey(), Noise.generatePrivate(), "sahte$it")
            victim.ingest(id, fake.selfPresence(id)!!)
        }
        assertTrue(victim.contacts(id).size <= MeshEngine.MAX_CONTACTS)
        assertTrue(victim.contacts(id).any { it.nick == "dost" && it.proven }, "kanıtlı kişi korunmalı")
    }

    @Test
    fun idleMeshPeersConvergeAndNewMessagesStillFlow() {
        val pa = freePort()
        val pb = freePort()
        val a = node("a", pa, listOf(pb))
        val b = node("b", pb, listOf(pa))
        val net = a.createNetwork("n")
        b.joinNetwork(net.code, "n")
        a.sendPublicMessage(net.id, "bir")
        waitFor("bir") { b.meshMessages(net.id).any { it.text == "bir" } }
        Thread.sleep(800) // birkaç boş eşitleme turu: özetler aynı
        assertEquals(1, b.meshMessages(net.id).count { it.text == "bir" })
        b.sendPublicMessage(net.id, "iki")
        waitFor("iki") { a.meshMessages(net.id).any { it.text == "iki" } }
        assertFalse(a.meshMessages(net.id).isEmpty())
    }
}
